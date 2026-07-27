package com.pinlog.pinlogback.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pinlog.pinlogback.domain.sample.EnvelopeTestController;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PostgreSQLContainer}는 {@link PostgresContainerSupport}가 정적으로 시작해두므로 여기서는
 * 직접 사용하지 않는다({@code @Testcontainers}는 다른 통합 테스트와의 관례를 맞추기 위해 유지).
 */
@Testcontainers
@Import(EnvelopeTestController.class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "management.health.redis.enabled=false"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiResponseOpenApiCustomizerTest extends PostgresContainerSupport {

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Value("${local.server.port}")
	private int port;

	@Test
	void domainOperationResponseSchemaIsWrappedInEnvelope() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/core/v3/api-docs"))
			.GET()
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);

		JsonNode root = jsonMapper.readTree(response.body());
		JsonNode content = root.path("paths").path("/test-envelope/dto").path("get")
			.path("responses").path("200")
			.path("content");

		// EnvelopeTestController#dto()는 produces를 선언하지 않아 springdoc이 "*/*"로 문서화한다.
		// 미디어 타입 키를 가정하지 않고 실제로 문서화된 첫 항목을 그대로 사용한다.
		assertThat(content.isMissingNode()).isFalse();
		Iterator<JsonNode> mediaTypes = content.values().iterator();
		assertThat(mediaTypes.hasNext()).isTrue();
		JsonNode schema = mediaTypes.next().path("schema");

		assertThat(schema.isMissingNode()).isFalse();
		// 스키마가 $ref로 표현될 수 있으므로, 참조라면 components.schemas까지 따라가서 검증한다
		// (테스트를 약화시키지 않고 참조를 따라간다).
		schema = resolveRef(root, schema);

		String schemaText = schema.toString();
		assertThat(schemaText).contains("success");
		assertThat(schemaText).contains("data");
	}

	private JsonNode resolveRef(JsonNode root, JsonNode schema) {
		JsonNode refNode = schema.path("$ref");
		if (refNode.isMissingNode()) {
			return schema;
		}
		String ref = refNode.asString();
		String componentName = ref.substring(ref.lastIndexOf('/') + 1);
		return root.path("components").path("schemas").path(componentName);
	}
}
