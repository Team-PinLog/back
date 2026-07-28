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

import com.pinlog.pinlogback.integration.PostgresContainerSupport;

/**
 * 인증 도입으로 생긴 경로·응답 계약을 검증한다.
 *
 * <p>Security 필터 체인은 {@code @RestControllerAdvice} 바깥에서 동작하므로
 * 401·403 응답이 공통 envelope를 따르는지 별도로 확인해야 한다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "management.health.redis.enabled=false"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("인증 경로·응답 계약")
class SecurityContractTests extends PostgresContainerSupport {

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

	@Test
	@DisplayName("보호 경로에 인증 없이 접근하면 401을 반환한다")
	void protectedPathReturnsUnauthorized() throws Exception {
		assertThat(get("/api/core/v1/me/summary").statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("401 응답도 공통 envelope와 traceId를 따른다")
	void unauthorizedResponseFollowsErrorContract() throws Exception {
		HttpResponse<String> response = get("/api/core/v1/me/summary");

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
