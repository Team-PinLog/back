package com.pinlog.pinlogback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@Testcontainers
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "management.health.redis.enabled=false"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiDocsTests extends PostgresContainerSupport {

	@Container
	static final PostgreSQLContainer<?> postgres = POSTGRES;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	void openApiDocsAreGeneratedUnderServiceContextPath() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/core/v3/api-docs"))
			.GET()
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"openapi\""));
	}
}
