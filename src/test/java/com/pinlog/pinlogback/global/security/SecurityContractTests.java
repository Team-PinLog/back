package com.pinlog.pinlogback.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 인증 도입으로 생긴 경로·응답 계약을 검증한다.
 *
 * <p>Security 필터 체인은 {@code @RestControllerAdvice} 바깥에서 동작하므로
 * 401·403 응답이 공통 envelope를 따르는지 별도로 확인해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("인증 경로·응답 계약")
class SecurityContractTests extends IntegrationContainerSupport {

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	@DisplayName("헬스체크와 지표는 인증 없이 열려 있다")
	void actuatorProbesStayPublic() throws Exception {
		// 이 경로가 막히면 배포 헬스체크가 실패한다(authentication.md 3).
		assertThat(get("/api/core/actuator/health").statusCode()).isEqualTo(200);
		assertThat(get("/api/core/actuator/health/liveness").statusCode()).isEqualTo(200);
		assertThat(get("/api/core/actuator/health/readiness").statusCode()).isEqualTo(200);
		assertThat(get("/api/core/actuator/prometheus").statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("API 문서는 인증 없이 열려 있다")
	void apiDocsStayPublic() throws Exception {
		assertThat(get("/api/core/v3/api-docs").statusCode()).isEqualTo(200);
	}

	/**
	 * <b>매핑된 도메인 엔드포인트를 쓴다.</b> 전에는 존재하지 않는 {@code /v1/me/summary}를 때려서
	 * 실제로 확인한 것이 "매핑되지 않은 URL도 401"뿐이었고, 그건
	 * {@code DeploymentContractTests.unmappedServiceUrlRequiresAuthentication}과 같은 내용이었다.
	 *
	 * <p><b>다만 이 테스트가 "인가 규칙이 이 경로를 막는다"를 증명하지는 않는다.</b> 실험으로
	 * 확인했다 — {@code /v1/collections}를 {@code permitAll}에 넣어도 이 테스트는 통과한다.
	 * {@code LoginMemberArgumentResolver}가 fail-closed라 인증이 없으면 거기서 401을 던지기
	 * 때문이다. 두 겹(인가 규칙 · 리졸버) 중 어느 쪽이 막았는지는 응답만 봐서 구분되지 않는다.
	 *
	 * <p>그래서 이 테스트가 고정하는 것은 <b>바깥에서 관측 가능한 계약</b>이다 — 실재하는 보호
	 * 경로에 미인증으로 들어가면 401이 공통 envelope로 나온다. 어느 층이 막았는지는 계약이 아니다.
	 */
	private static final String PROTECTED_PATH = "/api/core/v1/collections";

	@Test
	@DisplayName("보호 경로에 인증 없이 접근하면 401을 반환한다")
	void protectedPathReturnsUnauthorized() throws Exception {
		assertThat(get(PROTECTED_PATH).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("401 응답도 공통 envelope와 traceId를 따른다")
	void unauthorizedResponseFollowsErrorContract() throws Exception {
		HttpResponse<String> response = get(PROTECTED_PATH);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body())
			.contains("\"success\":false")
			.contains("\"code\":\"UNAUTHORIZED\"");
		// traceId가 null이면 TraceIdFilter가 Security 필터 체인보다 늦게 도는 것이다.
		assertThat(response.body()).doesNotContain("\"traceId\":null");
		assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
	}

	@Test
	@DisplayName("CSRF 토큰이 없는 변경 요청은 403을 반환한다")
	void stateChangingRequestWithoutCsrfTokenIsForbidden() throws Exception {
		HttpResponse<String> response = post("/api/core/v1/auth/logout");

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body())
			.contains("\"success\":false")
			.contains("\"code\":\"FORBIDDEN\"");
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return send(HttpRequest.newBuilder().uri(uri(path)).GET().build());
	}

	private HttpResponse<String> post(String path) throws IOException, InterruptedException {
		return send(HttpRequest.newBuilder()
			.uri(uri(path))
			.POST(HttpRequest.BodyPublishers.noBody())
			.build());
	}

	private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
