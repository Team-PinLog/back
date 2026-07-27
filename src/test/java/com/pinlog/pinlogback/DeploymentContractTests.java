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

import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "management.health.redis.enabled=false"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeploymentContractTests extends PostgresContainerSupport {

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	void healthEndpointIsAvailableUnderServiceContextPath() throws Exception {
		HttpResponse<String> response = get("/api/core/actuator/health");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"status\":\"UP\""));
	}

	@Test
	void prometheusEndpointIsAvailableForMonitoring() throws Exception {
		HttpResponse<String> response = get("/api/core/actuator/prometheus");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("jvm_"));
	}

	@Test
	void actuatorIsNotAvailableOutsideServiceContextPath() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertEquals(404, response.statusCode());
	}

	@Test
	void unmappedServiceUrlIsNotBlockedByAuthentication() throws Exception {
		HttpResponse<String> response = get("/api/core/not-found");

		assertEquals(404, response.statusCode());
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.GET()
			.build();

		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
