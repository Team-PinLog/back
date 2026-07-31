package com.pinlog.pinlogback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiDocsTests extends IntegrationContainerSupport {

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Value("${local.server.port}")
	private int port;

	@Test
	void openApiDocsAreGeneratedUnderServiceContextPath() throws Exception {
		HttpResponse<String> response = fetchApiDocs();

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"openapi\""));
	}

	@Test
	void openApiInfoHasServiceTitle() throws Exception {
		HttpResponse<String> response = fetchApiDocs();

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"title\":\"PinLog Core API\""));
	}

	/**
	 * Collection 상세는 소유 여부에 따라 상속 관계 없는 두 DTO 중 하나를 돌려주고 컨트롤러 선언 타입은
	 * {@code Object}다(BD-13). 선언 타입으로는 springdoc이 아무것도 알아낼 수 없어 애노테이션으로 알려 준다 —
	 * 그 결과가 런타임 계약(둘 중 하나)과 같은 {@code oneOf}인지 고정한다.
	 */
	@Test
	void collectionDetailResponseIsDocumentedAsOneOfOwnerAndPublicDto() throws Exception {
		JsonNode docs = objectMapper.readTree(fetchApiDocs().body());

		JsonNode content = docs.at(
			"/paths/~1v1~1collections~1{collectionId}/get/responses/200/content");
		assertFalse(content.isMissingNode(), "Collection 상세의 200 응답에 content가 없다");

		List<String> refs = new ArrayList<>();
		content.forEach(mediaType -> mediaType.at("/schema/properties/data/oneOf")
			.forEach(candidate -> refs.add(candidate.path("$ref").asText())));

		assertTrue(refs.contains("#/components/schemas/CollectionDetailResponse"),
			"소유자용 DTO가 oneOf에 없다: " + refs);
		assertTrue(refs.contains("#/components/schemas/PublicCollectionDetailResponse"),
			"공개용 DTO가 oneOf에 없다: " + refs);
	}

	private HttpResponse<String> fetchApiDocs() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/core/v3/api-docs"))
			.GET()
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
