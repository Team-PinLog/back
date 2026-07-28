package com.pinlog.pinlogback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * DB 장애 시 readiness가 트래픽에서 빠지는지 검증한다(BD-28).
 *
 * <p>공유 Testcontainers 컨테이너는 JVM 전체가 하나를 쓰므로(BT-01) 멈추지 않는다. 대신 닫혀 있는
 * 포트를 향하는 DataSource로 "DB에 닿지 않는 상태"를 만든다. 그래서 이 클래스는
 * {@code IntegrationContainerSupport}를 상속하지 않는다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:postgresql://localhost:1/pinlog",
		"spring.datasource.username=pinlog",
		"spring.datasource.password=pinlog",
		// DB에 닿지 않아도 컨텍스트가 떠야 health를 조회할 수 있다. 기동 시 풀 초기화 실패를
		// 허용하고(-1), health 조회가 매달리지 않도록 연결 대기를 짧게 둔다.
		"spring.datasource.hikari.initialization-fail-timeout=-1",
		"spring.datasource.hikari.connection-timeout=250",
		// 기동 시 DB를 만지는 단계를 끈다. 켜 두면 컨텍스트 로딩 자체가 실패해 health를 볼 수 없다.
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
		"management.health.redis.enabled=false"
	}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReadinessProbeDatabaseOutageTests {

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	void readinessIsNotUpWhenDatabaseIsUnreachable() throws Exception {
		HttpResponse<String> response = get("/api/core/actuator/health/readiness");

		assertEquals(503, response.statusCode());
		assertFalse(response.body().contains("\"status\":\"UP\""));
	}

	@Test
	void livenessStaysUpWhenDatabaseIsUnreachable() throws Exception {
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
