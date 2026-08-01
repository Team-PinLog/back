package com.pinlog.pinlogback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * backend-ci의 실행 시간을 좌우하는 결정을 정적으로 고정한다.
 *
 * <p>세 가지가 무너지면 PR 대기 시간이 조용히 원래대로 돌아간다: 러너에서 의미가 없는 {@code clean},
 * 이미지 빌드가 러너의 컴파일을 컨테이너 안에서 되풀이하지 않는다는 것, 문서만 바꾼 PR이 테스트와 이미지
 * 빌드를 건너뛰는 조건. 어느 것도 로컬에서는 드러나지 않으므로(CI에서만 관측된다) 워크플로 파일 자체를
 * 계약으로 읽어 검증한다.
 */
class BackendCiSpeedContractTests {

	private static final Path WORKFLOW = Path.of(".github/workflows/backend-ci.yml");
	private static final Path DOCKERFILE = Path.of("Dockerfile");

	/** 문서 전용 변경을 감지하는 스텝의 출력. 무거운 스텝은 전부 이 조건 뒤에 있어야 한다. */
	private static final String DOCS_ONLY_GUARD = "steps.scope.outputs.docs_only != 'true'";

	/** 러너가 만든 jar를 발행 잡으로 넘기는 아티팩트. 두 잡이 같은 이름을 봐야 전달이 성립한다. */
	private static final String JAR_ARTIFACT = "backend-jar";

	private static final String CHECK = "check";
	private static final String IMAGE_PUBLISH = "image-publish";
	private static final String VALIDATE_IMAGE = "Validate backend container image";
	private static final String PUBLISH_IMAGE = "Build and publish immutable backend image";

	/**
	 * 새 러너의 {@code build/}는 비어 있어 {@code clean}이 지울 것이 없고, Gradle이 스스로 판단할
	 * 최신 여부만 버린다. jar는 검사와 <b>같은 호출</b>에서 만든다 — 따로 부르면 Gradle 시작 비용을
	 * 한 번 더 내고, 같은 호출이면 컴파일된 클래스를 그대로 써 {@code :bootJar} 태스크 1.0초만 든다.
	 */
	@Test
	void oneGradleInvocationRunsChecksAndProducesTheJar() throws IOException {
		assertThat(String.valueOf(step(CHECK, "Run checks").get("run")))
			.contains("./gradlew check bootJar")
			.doesNotContain("clean");
	}

	/**
	 * 이미지 빌드는 러너가 이미 만든 jar를 받는다. 컨테이너 안에서 Gradle을 다시 돌리면 같은 컴파일을
	 * 두 번 하는 것이고, 그 레이어는 {@code src}가 매 PR 바뀌므로 캐시로도 지울 수 없다.
	 *
	 * <p><b>그래서 레이어 캐시를 선언하지 않는다.</b> 캐시할 대상이 사라졌는데 설정만 남기면 죽은 설정이
	 * 되고, {@code mode=max} 내보내기는 dev 발행 빌드에 91.4초를 되돌려 놓는다.
	 */
	@Test
	void imageBuildsTakeThePrebuiltJarAndSoDeclareNoLayerCache() throws IOException {
		// 주석은 뺀다. 이 파일은 `./gradlew bootJar`가 선행이라는 것을 주석으로 알려야 하므로,
		// 문자열을 통째로 보면 그 설명이 실행으로 오인된다. 판정 대상은 명령줄이다.
		String instructions = Files.readString(DOCKERFILE).lines()
			.filter(line -> !line.stripLeading().startsWith("#"))
			.collect(Collectors.joining("\n"));
		assertThat(instructions)
			.as("Dockerfile이 컨테이너 안에서 Gradle을 돌리면 러너의 컴파일이 중복된다")
			.doesNotContain("gradlew");

		for (String identity : List.of(VALIDATE_IMAGE, PUBLISH_IMAGE)) {
			String job = VALIDATE_IMAGE.equals(identity) ? CHECK : IMAGE_PUBLISH;
			assertThat(map(step(job, identity).get("with")))
				.as("스텝 '%s'에 캐시할 대상이 없는데 캐시 설정이 남아 있다", identity)
				.doesNotContainKeys("cache-from", "cache-to");
		}
	}

	/**
	 * {@code image-publish}는 별도 잡이라 러너의 {@code build/}를 물려받지 못한다. 그 잡에서 Gradle을
	 * 다시 돌리면 없애려던 중복이 되살아나므로, {@code check}가 만든 jar를 아티팩트로 넘긴다.
	 *
	 * <p>PR 검증은 {@code check} 안에서 이뤄져 아티팩트가 필요 없다 — 그래서 업로드는 dev push에서만 한다.
	 */
	@Test
	void devPushHandsTheRunnerBuiltJarToThePublishJob() throws IOException {
		Map<Object, Object> upload = stepUsing(CHECK, "actions/upload-artifact", JAR_ARTIFACT);
		assertThat(String.valueOf(upload.get("if")))
			.as("PR은 같은 잡에서 이미지를 빌드하므로 업로드가 필요 없다")
			.contains("github.event_name == 'push'");

		Map<Object, Object> download = stepUsing(IMAGE_PUBLISH, "actions/download-artifact", null);
		assertThat(map(download.get("with")).get("name"))
			.as("두 잡이 같은 아티팩트 이름을 봐야 전달이 성립한다")
			.isEqualTo(JAR_ARTIFACT);
	}

	/**
	 * {@code backend-ci / check}는 dev의 필수 상태 검사다. 그래서 잡 자체는 항상 돌아 결과를 보고하고,
	 * 건너뛰기는 <b>잡 안의 스텝</b>에서만 일어나야 한다. 워크플로 수준 {@code paths-ignore}로 잡을
	 * 아예 실행하지 않으면 검사가 대기 상태로 남아 문서 PR을 머지할 수 없다.
	 */
	@Test
	void documentationOnlyPullRequestsStillReportButSkipTheExpensiveSteps() throws IOException {
		Map<Object, Object> check = job(CHECK);
		assertThat(check.get("name")).isEqualTo("backend-ci / check");
		assertThat(check).as("필수 상태 검사인 잡은 조건 없이 항상 실행돼야 한다").doesNotContainKey("if");
		assertThat(map(load().get(Boolean.TRUE)).values())
			.as("경로 기반 제외는 잡을 건너뛰게 해 필수 검사를 대기 상태로 남긴다")
			.allSatisfy(trigger -> assertThat(map(trigger)).doesNotContainKeys("paths", "paths-ignore"));

		assertThat(stepsByIdentity(CHECK)).containsKey("Detect documentation-only changes");

		for (Map<Object, Object> expensive : List.of(
			stepUsing(CHECK, "gradle/actions/setup-gradle"),
			step(CHECK, "Run checks"),
			stepUsing(CHECK, "docker/setup-buildx-action"),
			step(CHECK, "Validate backend container image")
		)) {
			assertThat(String.valueOf(expensive.get("if")))
				.as("스텝 %s가 문서 전용 변경에서도 실행된다", expensive)
				.contains(DOCS_ONLY_GUARD);
		}
	}

	private Map<Object, Object> job(String name) throws IOException {
		Map<Object, Object> job = map(map(load().get("jobs")).get(name));
		assertThat(job).as("잡 '%s'이 없다", name).isNotNull();
		return job;
	}

	/**
	 * 스텝을 신원(이름, 없으면 {@code uses})으로 색인한다. 위치로 집으면 스텝이 중간에 삽입될 때
	 * 무관한 단언이 엉뚱한 스텝을 검사하며 깨진다.
	 */
	private Map<String, Map<Object, Object>> stepsByIdentity(String jobName) throws IOException {
		List<Object> steps = list(job(jobName).get("steps"));
		Map<String, Map<Object, Object>> byIdentity = new LinkedHashMap<>();
		for (Object each : steps) {
			Map<Object, Object> step = map(each);
			byIdentity.put(String.valueOf(step.getOrDefault("name", step.get("uses"))), step);
		}
		assertThat(byIdentity)
			.as("신원이 겹치는 스텝이 있어 색인에서 가려졌다: %s", steps)
			.hasSameSizeAs(steps);
		return byIdentity;
	}

	private Map<Object, Object> step(String jobName, String identity) throws IOException {
		Map<Object, Object> step = stepsByIdentity(jobName).get(identity);
		assertThat(step).as("잡 '%s'에 스텝 '%s'이 없다", jobName, identity).isNotNull();
		return step;
	}

	/** action은 SHA로 고정하므로 갱신될 때마다 부러지지 않도록 접두어로 찾는다. */
	private Map<Object, Object> stepUsing(String jobName, String actionPrefix) throws IOException {
		return stepUsing(jobName, actionPrefix, null);
	}

	/**
	 * 같은 action을 쓰는 스텝이 한 잡에 둘 이상일 수 있다({@code check}는 리포트와 jar를 각각 올린다).
	 * 그때는 {@code with.name}으로 갈라야 엉뚱한 스텝을 집지 않는다.
	 */
	private Map<Object, Object> stepUsing(String jobName, String actionPrefix, String artifactName)
		throws IOException {
		List<Map<Object, Object>> found = stepsByIdentity(jobName).values().stream()
			.filter(step -> String.valueOf(step.get("uses")).startsWith(actionPrefix))
			.filter(step -> artifactName == null
				|| artifactName.equals(map(step.get("with")).get("name")))
			.toList();
		assertThat(found)
			.as("잡 '%s'에서 '%s'(아티팩트 %s) 스텝을 하나만 찾지 못했다", jobName, actionPrefix, artifactName)
			.hasSize(1);
		return found.get(0);
	}

	@SuppressWarnings("unchecked")
	private Map<Object, Object> load() throws IOException {
		try (InputStream input = Files.newInputStream(WORKFLOW)) {
			return (Map<Object, Object>)new Yaml().load(input);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<Object, Object> map(Object value) {
		return (Map<Object, Object>)value;
	}

	@SuppressWarnings("unchecked")
	private List<Object> list(Object value) {
		return (List<Object>)value;
	}
}
