package com.pinlog.pinlogback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
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

	@Value("${spring.lifecycle.timeout-per-shutdown-phase}")
	private Duration shutdownTimeout;

	@Autowired
	private ServerProperties serverProperties;

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
	void livenessProbeIsAvailableForKubernetes() throws Exception {
		HttpResponse<String> response = get("/api/core/actuator/health/liveness");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"status\":\"UP\""));
	}

	@Test
	void readinessProbeIsAvailableForKubernetes() throws Exception {
		HttpResponse<String> response = get("/api/core/actuator/health/readiness");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"status\":\"UP\""));
	}

	@Test
	void inFlightRequestsAreDrainedOnSigterm() {
		assertEquals(Shutdown.GRACEFUL, serverProperties.getShutdown());
	}

	@Test
	void shutdownTimeoutFitsInsideKubernetesTerminationGracePeriod() {
		assertEquals(Duration.ofSeconds(20), shutdownTimeout);
	}

	@Test
	void actuatorIsNotAvailableOutsideServiceContextPath() throws Exception {
		HttpResponse<String> response = get("/actuator/health");

		assertEquals(404, response.statusCode());
	}

	@Test
	void unmappedServiceUrlRequiresAuthentication() throws Exception {
		// 인증 도입 전에는 404였다. 이제 매핑 여부와 무관하게 보호 경로는 401이며,
		// 미인증과 "없는 경로"를 구분하지 않는다(08 §1.1·§1.2).
		HttpResponse<String> response = get("/api/core/not-found");

		assertEquals(401, response.statusCode());
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.GET()
			.build();

		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
