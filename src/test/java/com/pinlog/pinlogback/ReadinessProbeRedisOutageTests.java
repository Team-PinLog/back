package com.pinlog.pinlogback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * Redis 장애가 readiness로 번지지 않는지 검증한다(BD-28).
 *
 * <p>인프라 정책은 readiness에 PostgreSQL만 넣고 Redis는 제외한다. Redis가 죽어도 Pod가 트래픽에서
 * 빠지지 않아야 한다. Redis health indicator를 켠 채 닫혀 있는 포트를 향하게 해서 확인한다.
 *
 * <p>Lettuce 기본 command timeout은 60초여서 그대로 두면 테스트가 매달린다(BT-03). 여기서 줄이는
 * 값은 테스트 전용이며, 운영 값은 back#65에서 따로 정한다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"management.health.redis.enabled=true",
		"spring.data.redis.host=localhost",
		"spring.data.redis.port=1",
		"spring.data.redis.timeout=250ms",
		"spring.data.redis.connect-timeout=250ms"
	}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessProbeRedisOutageTests extends IntegrationContainerSupport {

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	void readinessStaysUpWhenRedisIsUnreachable() throws Exception {
		HttpResponse<String> response = get("/api/core/actuator/health/readiness");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"status\":\"UP\""));
	}

	@Test
	void livenessStaysUpWhenRedisIsUnreachable() throws Exception {
		HttpResponse<String> response = get("/api/core/actuator/health/liveness");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"status\":\"UP\""));
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.GET()
			.build();

		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
