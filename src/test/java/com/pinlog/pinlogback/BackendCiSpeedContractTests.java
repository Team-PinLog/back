package com.pinlog.pinlogback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * backend-ci의 실행 시간을 좌우하는 결정을 정적으로 고정한다.
 *
 * <p>세 가지가 무너지면 PR 대기 시간이 조용히 원래대로 돌아간다: 러너에서 의미가 없는 {@code clean},
 * 이미지 빌드의 레이어 캐시, 문서만 바꾼 PR이 테스트와 이미지 빌드를 건너뛰는 조건. 어느 것도 로컬에서는
 * 드러나지 않으므로(CI에서만 관측된다) 워크플로 파일 자체를 계약으로 읽어 검증한다.
 */
class BackendCiSpeedContractTests {

	private static final Path WORKFLOW = Path.of(".github/workflows/backend-ci.yml");

	/** 문서 전용 변경을 감지하는 스텝의 출력. 무거운 스텝은 전부 이 조건 뒤에 있어야 한다. */
	private static final String DOCS_ONLY_GUARD = "steps.scope.outputs.docs_only != 'true'";

	/** PR 검증 빌드와 dev 발행 빌드가 <b>같은</b> 캐시를 봐야 재사용이 일어난다. */
	private static final String IMAGE_CACHE = "type=gha,scope=backend-image";

	private static final String CHECK = "check";
	private static final String IMAGE_PUBLISH = "image-publish";

	/**
	 * 새 러너의 {@code build/}는 비어 있어 {@code clean}이 지울 것이 없고, Gradle이 스스로 판단할
	 * 최신 여부만 버린다.
	 */
	@Test
	void gradleRunsWithoutACleanThatHasNothingToClean() throws IOException {
		assertThat(String.valueOf(step(CHECK, "Run checks").get("run")))
			.contains("./gradlew check")
			.doesNotContain("clean");
	}

	/**
	 * 두 빌드가 같은 {@code Dockerfile}을 쓰므로 캐시 범위를 공유한다. 쓰기는 dev 발행 쪽만 한다 —
	 * 저장소 캐시 용량은 공유 자원이고, 기본 브랜치가 쓴 항목은 모든 PR이 읽을 수 있어 PR이 따로
	 * 쓸 이유가 없다.
	 */
	@Test
	void bothImageBuildsShareOneLayerCacheAndOnlyTheDevPushWritesIt() throws IOException {
		Map<Object, Object> validate = map(step(CHECK, "Validate backend container image").get("with"));
		assertThat(validate.get("cache-from")).isEqualTo(IMAGE_CACHE);
		assertThat(validate)
			.as("PR 검증 빌드가 캐시를 쓰면 브랜치마다 항목이 쌓여 기본 브랜치 항목을 밀어낸다")
			.doesNotContainKey("cache-to");

		Map<Object, Object> publish =
			map(step(IMAGE_PUBLISH, "Build and publish immutable backend image").get("with"));
		assertThat(publish.get("cache-from")).isEqualTo(IMAGE_CACHE);
		assertThat(publish.get("cache-to")).isEqualTo(IMAGE_CACHE + ",mode=max");
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
		List<Map<Object, Object>> found = stepsByIdentity(jobName).values().stream()
			.filter(step -> String.valueOf(step.get("uses")).startsWith(actionPrefix))
			.toList();
		assertThat(found).as("잡 '%s'에서 '%s' 스텝을 하나만 찾지 못했다", jobName, actionPrefix).hasSize(1);
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
