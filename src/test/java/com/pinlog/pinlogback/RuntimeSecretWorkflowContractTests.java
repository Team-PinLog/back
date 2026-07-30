package com.pinlog.pinlogback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 운영 Secret 전달 workflow가 검토된 최소 권한 계약에서 벗어나지 않는지 정적으로 검증한다.
 *
 * <p>실제 Secret 값이나 GitHub Environment에는 접근하지 않는다. 이 테스트의 단일 원본은
 * {@code Team-PinLog/infra}의 {@code back-prod} policy와 SHA로 고정된 composite action이다.
 */
class RuntimeSecretWorkflowContractTests {

	private static final Path WORKFLOW = Path.of(".github/workflows/seal-runtime-secrets.yml");
	private static final Path BACKEND_CI = Path.of(".github/workflows/backend-ci.yml");
	private static final String ACTION =
		"Team-PinLog/infra/.github/actions/sealedsecret-infra-pr"
			+ "@ee325683ee2fb99ee47b07c11d02396b33701e0b";

	/** 스텝을 이름으로 집는다. checkout은 {@code name}이 없어 {@code uses}가 신원이다. */
	private static final String CHECKOUT = "actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683";
	private static final String DIAGNOSE_STEP = "Diagnose OIDC endpoint metadata safely";
	private static final String INFRA_PR_STEP = "Create canonical Infra SealedSecret Draft PR";
	private static final List<String> OWNER_SECRETS = List.of(
		"JWT_PRIVATE_KEY",
		"GOOGLE_CLIENT_ID",
		"GOOGLE_CLIENT_SECRET",
		"KAKAO_CLIENT_ID",
		"KAKAO_CLIENT_SECRET",
		"NAVER_CLIENT_ID",
		"NAVER_CLIENT_SECRET",
		"PINLOG_AI_INTERNAL_SECRET"
	);
	private static final String BRIDGE_SECRET = "PINLOG_INFRA_SECRET_PR_TOKEN";

	@Test
	void workflowHasOnlyTheManualTriggerAndMinimumPermissions() throws IOException {
		Map<Object, Object> workflow = load(WORKFLOW);

		// SnakeYAML follows YAML 1.1 and resolves the unquoted GitHub key "on" as Boolean.TRUE.
		assertThat(map(workflow.get(Boolean.TRUE))).containsOnlyKeys("workflow_dispatch");
		assertThat(map(workflow.get("permissions"))).containsExactlyInAnyOrderEntriesOf(Map.of(
			"contents", "read",
			"id-token", "write"
		));
		assertThat(map(workflow.get("jobs"))).containsOnlyKeys("seal-runtime-secrets");
	}

	@Test
	void secretJobHasExactlyTheReviewedEnvironmentAndSteps() throws IOException {
		Map<Object, Object> job = secretJob();

		assertThat(job).containsOnlyKeys("name", "runs-on", "environment", "steps");
		assertThat(job.get("runs-on")).isEqualTo("ubuntu-latest");
		assertThat(job.get("environment")).isEqualTo("pinlog-secrets-prod");
		// 개수가 아니라 신원의 집합을 고정한다. 스텝이 하나 늘면 여기서 그 이름이 드러나므로
		// "검토된 스텝만 있다"는 보증은 그대로이고, 아래 상세 단언은 순서에 영향받지 않는다.
		assertThat(stepsByIdentity().keySet()).containsExactlyInAnyOrder(
			CHECKOUT, DIAGNOSE_STEP, INFRA_PR_STEP);

		Map<Object, Object> checkout = step(CHECKOUT);
		assertThat(checkout).containsOnlyKeys("uses", "with");
		assertThat(map(checkout.get("with"))).containsExactlyInAnyOrderEntriesOf(Map.of(
			"ref", "${{ github.sha }}",
			"persist-credentials", false
		));
	}

	/**
	 * 진단 스텝은 이 Environment 경계 안에서 도는 <b>임의 스크립트</b>다. 그래서 계약은 하나다 —
	 * 런타임 Secret을 참조하지 않는다. 참조하면 그 값이 공개 저장소의 Actions 로그로 나갈 수 있다.
	 */
	@Test
	void diagnosticStepCannotReadRuntimeOrBridgeSecrets() throws IOException {
		Map<Object, Object> diagnose = step(DIAGNOSE_STEP);

		assertThat(diagnose).containsOnlyKeys("name", "shell", "run");
		assertThat(String.valueOf(diagnose.get("run"))).doesNotContain("secrets.");
	}

	@Test
	void infraActionHasExactlyTheReviewedInputsAndSecretMapping() throws IOException {
		Map<Object, Object> action = step(INFRA_PR_STEP);

		assertThat(action).containsOnlyKeys("name", "uses", "env", "with");
		assertThat(action.get("uses")).isEqualTo(ACTION);
		assertThat(map(action.get("with"))).containsExactlyInAnyOrderEntriesOf(Map.of(
			"policy", "back-prod",
			"revision", "${{ github.sha }}"
		));

		Map<Object, Object> environment = map(action.get("env"));
		assertThat(environment.keySet()).containsExactlyElementsOf(
			List.of(
				"JWT_PRIVATE_KEY", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET",
				"KAKAO_CLIENT_ID", "KAKAO_CLIENT_SECRET", "NAVER_CLIENT_ID",
				"NAVER_CLIENT_SECRET", "PINLOG_AI_INTERNAL_SECRET", BRIDGE_SECRET
			)
		);
		for (String secret : OWNER_SECRETS) {
			assertThat(environment.get(secret)).isEqualTo("${{ secrets." + secret + " }}");
		}
		assertThat(OWNER_SECRETS).doesNotContain(BRIDGE_SECRET);
		assertThat(environment.get(BRIDGE_SECRET))
			.isEqualTo("${{ secrets.PINLOG_INFRA_SECRET_PR_TOKEN }}");
		assertThat(action).doesNotContainKey("run");
	}

	@Test
	void backendCiCannotReadRuntimeOrBridgeSecrets() throws IOException {
		String backendCi = Files.readString(BACKEND_CI);
		String allowedRepositoryToken = "${{ secrets.GITHUB_TOKEN }}";

		assertThat(backendCi).containsOnlyOnce(allowedRepositoryToken);
		assertThat(backendCi.replace(allowedRepositoryToken, "").toLowerCase(Locale.ROOT))
			.doesNotContain("secrets");
	}

	private Map<Object, Object> secretJob() throws IOException {
		return map(map(load(WORKFLOW).get("jobs")).get("seal-runtime-secrets"));
	}

	/**
	 * 스텝을 신원(이름, 없으면 {@code uses})으로 색인한다. 위치로 집으면 스텝이 중간에 삽입될 때
	 * 무관한 단언이 엉뚱한 스텝을 검사하며 깨진다 — 실제로 #115~#119에서 그렇게 부러졌다.
	 */
	private Map<String, Map<Object, Object>> stepsByIdentity() throws IOException {
		Map<String, Map<Object, Object>> byIdentity = new LinkedHashMap<>();
		for (Object each : list(secretJob().get("steps"))) {
			Map<Object, Object> step = map(each);
			Object identity = step.getOrDefault("name", step.get("uses"));
			byIdentity.put(String.valueOf(identity), step);
		}
		return byIdentity;
	}

	private Map<Object, Object> step(String identity) throws IOException {
		Map<Object, Object> step = stepsByIdentity().get(identity);
		assertThat(step).as("스텝 '%s'이 없다", identity).isNotNull();
		return step;
	}

	@SuppressWarnings("unchecked")
	private Map<Object, Object> load(Path path) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
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
