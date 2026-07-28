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
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PostgreSQLContainer}는 {@link IntegrationContainerSupport}가 정적으로 시작해두므로 여기서는
 * 직접 사용하지 않는다({@code @Testcontainers}는 다른 통합 테스트와의 관례를 맞추기 위해 유지).
 */
@Testcontainers
@Import(EnvelopeTestController.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiResponseOpenApiCustomizerTest extends IntegrationContainerSupport {

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Value("${local.server.port}")
	private int port;

	@Test
	void domainOperationResponseSchemaIsWrappedInEnvelope() throws Exception {
		JsonNode root = fetchApiDocs();

		JsonNode schema = resolveRef(root, successSchemaOf(root, "/test-envelope/dto"));

		assertThat(schema.path("properties").has("success")).isTrue();
		JsonNode data = resolveRef(root, schema.path("properties").path("data"));
		assertThat(data.path("properties").has("name"))
			.as("data에는 DTO(Payload)가 그대로 들어가야 한다")
			.isTrue();
	}

	/**
	 * 선언 타입이 {@code ResponseEntity<ApiResponse<T>>}인 경우. 런타임 advice는 실제 body가 이미
	 * envelope이므로 감싸지 않는데, 문서 쪽이 선언 타입만 보고 "envelope 아님"으로 판정하면 한 번 더
	 * 감싸서 {@code data.data}가 생긴다. 그러면 문서가 실제 응답과 다른 형태를 약속하게 된다.
	 */
	@Test
	void responseEntityWrappedEnvelopeIsNotDocumentedTwice() throws Exception {
		JsonNode root = fetchApiDocs();

		JsonNode schema = resolveRef(root, successSchemaOf(root, "/test-envelope/entity-wrapped"));

		assertThat(schema.path("properties").has("success"))
			.as("envelope는 한 번은 적용돼 있어야 한다")
			.isTrue();
		JsonNode data = resolveRef(root, schema.path("properties").path("data"));
		assertThat(data.path("properties").has("success"))
			.as("data 안에 success가 또 있으면 envelope를 두 번 감싼 것이다")
			.isFalse();
		assertThat(data.path("properties").has("name"))
			.as("data에는 DTO(Payload)가 들어가야 한다")
			.isTrue();
	}

	@Test
	void directEnvelopeReturnIsNotDocumentedTwice() throws Exception {
		JsonNode root = fetchApiDocs();

		JsonNode schema = resolveRef(root, successSchemaOf(root, "/test-envelope/already-wrapped"));

		JsonNode data = resolveRef(root, schema.path("properties").path("data"));
		assertThat(data.path("properties").has("success")).isFalse();
		assertThat(data.path("properties").has("name")).isTrue();
	}

	private JsonNode fetchApiDocs() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/core/v3/api-docs"))
			.GET()
			.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return jsonMapper.readTree(response.body());
	}

	/**
	 * 해당 경로의 200 응답 스키마를 꺼낸다. 테스트 컨트롤러가 {@code produces}를 선언하지 않아
	 * springdoc이 미디어 타입을 {@code "*&#47;*"}로 문서화하므로, 키를 가정하지 않고 첫 항목을 쓴다.
	 */
	private JsonNode successSchemaOf(JsonNode root, String path) {
		JsonNode content = root.path("paths").path(path).path("get")
			.path("responses").path("200")
			.path("content");
		assertThat(content.isMissingNode()).as("%s의 200 응답이 문서화되어야 한다", path).isFalse();

		Iterator<JsonNode> mediaTypes = content.values().iterator();
		assertThat(mediaTypes.hasNext()).isTrue();
		JsonNode schema = mediaTypes.next().path("schema");
		assertThat(schema.isMissingNode()).isFalse();
		return schema;
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
