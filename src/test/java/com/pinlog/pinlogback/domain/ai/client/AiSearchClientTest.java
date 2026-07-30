package com.pinlog.pinlogback.domain.ai.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

import com.pinlog.pinlogback.domain.ai.AiProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Embedding Profile 부재를 <b>기동 시점에</b> 끊는지 고정한다.
 *
 * <p>{@code application.yml}의 기본값은 변수를 <b>설정하지 않은</b> 경우만 막는다.
 * {@code PINLOG_AI_EMBEDDING_PROFILE=}처럼 빈 값으로 정의하면 빈 문자열이 기본값을 이기고, 그러면
 * FastAPI가 자기 Profile과 대조해 422를 주므로 결과는 <b>모든 검색이 503</b>이다. 이 레포는 같은
 * 형태를 한 번 겪었다(BT-05 — {@code .env.example}이 자격증명을 빈 값으로 정의한 건).
 *
 * <p>검사를 {@code AiProcessClient.requireSecret}과 같은 기준으로 가른다 — 운영은 기동 실패, 그
 * 외는 경고. {@code embedding-profile}은 <b>이 클라이언트만 읽는 키</b>라, 시크릿과 달리 다른
 * 클라이언트가 대신 검사해 주지 않는다.
 */
@DisplayName("AI 검색 클라이언트 기동 검사")
class AiSearchClientTest {

	private static final String PROFILE = "openai-text-embedding-3-small-1536-cosine-v1";

	@Test
	@DisplayName("운영 프로파일에서 Embedding Profile이 비어 있으면 기동에 실패한다")
	void failsFastInProductionWhenTheEmbeddingProfileIsBlank() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> newClient("", prod))
			.as("빈 값으로 정의된 환경변수는 기본값을 이긴다 — 첫 검색까지 아무도 모르면 안 된다")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PINLOG_AI_EMBEDDING_PROFILE");
		assertThatThrownBy(() -> newClient(null, prod))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PINLOG_AI_EMBEDDING_PROFILE");
	}

	@Test
	@DisplayName("운영이 아니면 경고만 남기고 기동한다")
	void startsWithAWarningOutsideProductionWhenTheEmbeddingProfileIsBlank() {
		// 로컬·테스트는 FastAPI 없이도 떠야 한다. 여기서 기동을 막으면 검색과 무관한 테스트가
		// 컨텍스트 실패로 무너진다 — BT-05가 겪은 것과 같은 형태의 손해다.
		assertThatCode(() -> newClient("", new MockEnvironment())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("값이 있으면 운영에서도 그대로 기동한다")
	void startsInProductionWhenTheEmbeddingProfileIsConfigured() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatCode(() -> newClient(PROFILE, prod)).doesNotThrowAnyException();
	}

	private AiSearchClient newClient(String embeddingProfile, MockEnvironment environment) {
		AiProperties.Timeouts timeouts =
			new AiProperties.Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(5));
		AiProperties properties = new AiProperties(
			"http://localhost:8000", "test-internal-secret", embeddingProfile, timeouts, timeouts);
		return new AiSearchClient(
			RestClient.create(), JsonMapper.builder().build(), properties, environment);
	}
}
