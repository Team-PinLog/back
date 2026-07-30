package com.pinlog.pinlogback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
			+ "@b3f26ab8909ed7732e15aa64f432a720ec531401";
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
		List<Object> steps = list(job.get("steps"));

		assertThat(job).containsOnlyKeys("name", "runs-on", "environment", "steps");
		assertThat(job.get("runs-on")).isEqualTo("ubuntu-latest");
		assertThat(job.get("environment")).isEqualTo("pinlog-secrets-prod");
		assertThat(steps).hasSize(2);

		Map<Object, Object> checkout = map(steps.get(0));
		assertThat(checkout).containsOnlyKeys("uses", "with");
		assertThat(checkout.get("uses"))
			.isEqualTo("actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683");
		assertThat(map(checkout.get("with"))).containsExactlyInAnyOrderEntriesOf(Map.of(
			"ref", "${{ github.sha }}",
			"persist-credentials", false
		));
	}

	@Test
	void infraActionHasExactlyTheReviewedInputsAndSecretMapping() throws IOException {
		Map<Object, Object> action = map(list(secretJob().get("steps")).get(1));

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
