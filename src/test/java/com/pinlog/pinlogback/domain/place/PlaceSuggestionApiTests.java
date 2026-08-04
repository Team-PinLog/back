package com.pinlog.pinlogback.domain.place;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

@SpringBootTest
@AutoConfigureMockMvc
class PlaceSuggestionApiTests extends IntegrationContainerSupport {

	private static final String URL = "/v1/places/suggestions";
	private static final FastApiPlaceSuggestionStub STUB = new FastApiPlaceSuggestionStub();

	@DynamicPropertySource
	static void aiServerPointsAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
		registry.add("pinlog.ai.place-suggestion.read-timeout", () -> "200ms");
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
		registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
	}

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void resetStub() {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.SUCCESS);
	}

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	@Test
	void relaysOneImageAndWrapsTheFastApiDataOnce() throws Exception {
		mockMvc.perform(multipart(URL)
				.file(image("chat.png", "image/png", "png-content".getBytes(StandardCharsets.UTF_8)))
				.with(loginAs(1L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.requestId").value("req_test"))
			.andExpect(jsonPath("$.data.success").doesNotExist())
			.andExpect(jsonPath("$.data.candidates[0].extracted.placeName").value("Zootopia Seoul"))
			.andExpect(jsonPath("$.data.candidates[0].extracted.contextSuggestion")
				.value("A pizza place only in Daegu and Seoul"))
			.andExpect(jsonPath("$.data.candidates[0].kakaoSearch.items[0].kakaoPlaceId").value("12345"));

		FastApiPlaceSuggestionStub.Received received = STUB.lastCall();
		assertThat(received).isNotNull();
		assertThat(received.internalSecret()).isEqualTo("test-internal-secret");
		assertThat(received.traceId()).isNotBlank();
		assertThat(received.contentType()).startsWith("multipart/form-data");
		assertThat(new String(received.body(), StandardCharsets.ISO_8859_1)).contains("chat.png", "png-content");
	}

	@Test
	void authenticationIsRequired() throws Exception {
		mockMvc.perform(multipart(URL)
				.file(image("chat.png", "image/png", new byte[] {1}))
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		assertThat(STUB.lastCall()).isNull();
	}

	@Test
	void csrfIsRequired() throws Exception {
		UsernamePasswordAuthenticationToken authentication =
			new UsernamePasswordAuthenticationToken(new MemberPrincipal(1L), null, java.util.List.of());
		mockMvc.perform(multipart(URL)
				.file(image("chat.png", "image/png", new byte[] {1}))
				.with(SecurityMockMvcRequestPostProcessors.authentication(authentication)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		assertThat(STUB.lastCall()).isNull();
	}

	@Test
	void rejectsMultipleImagesBeforeCallingFastApi() throws Exception {
		mockMvc.perform(multipart(URL)
				.file(image("one.png", "image/png", new byte[] {1}))
				.file(image("two.png", "image/png", new byte[] {2}))
				.with(loginAs(1L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_IMAGE_COUNT"));
		assertThat(STUB.lastCall()).isNull();
	}

	@Test
	void rejectsAnUnsupportedDeclaredMediaTypeBeforeCallingFastApi() throws Exception {
		mockMvc.perform(multipart(URL)
				.file(image("chat.webp", "image/webp", new byte[] {1}))
				.with(loginAs(1L)))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
		assertThat(STUB.lastCall()).isNull();
	}

	@Test
	void rejectsAnImageOverTheInitialFiveMiBLimit() throws Exception {
		byte[] oversized = new byte[5 * 1024 * 1024 + 1];
		mockMvc.perform(multipart(URL)
				.file(image("chat.png", "image/png", oversized))
				.with(loginAs(1L)))
			.andExpect(status().isContentTooLarge())
			.andExpect(jsonPath("$.error.code").value("IMAGE_TOO_LARGE"));
		assertThat(STUB.lastCall()).isNull();
	}

	@Test
	void mapsFastApiBusySeparatelyFromAnUpstreamOutage() throws Exception {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.BUSY);
		request().andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("PLACE_SUGGESTION_BUSY"));

		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.UNAVAILABLE);
		request().andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("PLACE_SUGGESTION_UNAVAILABLE"));
	}

	@Test
	void hidesAnInternalSecretFailureAsAnUpstreamError() throws Exception {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.UNAUTHORIZED);
		request().andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("PLACE_SUGGESTION_UPSTREAM_ERROR"));
	}

	@Test
	void mapsFastApiImageErrors() throws Exception {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.TOO_LARGE);
		request().andExpect(status().isContentTooLarge())
			.andExpect(jsonPath("$.error.code").value("IMAGE_TOO_LARGE"));

		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.UNSUPPORTED_MEDIA_TYPE);
		request().andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void mapsInvalidAndUpstreamFailuresWithoutPassingThroughFastApiBodies() throws Exception {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.BAD_REQUEST);
		request().andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_IMAGE"));

		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.UPSTREAM_ERROR);
		request().andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("PLACE_SUGGESTION_UPSTREAM_ERROR"));

		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.INTERNAL_ERROR);
		request().andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
	}

	@Test
	void mapsReadTimeoutToGatewayTimeout() throws Exception {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.TIMEOUT);
		request().andExpect(status().isGatewayTimeout())
			.andExpect(jsonPath("$.error.code").value("PLACE_SUGGESTION_TIMEOUT"));
	}

	@Test
	void rejectsASecondConcurrentAnalysisAsBusy() throws Exception {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.BLOCK);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> first = executor.submit(this::performSuccessfulRequest);
			assertThat(STUB.awaitRequest()).isTrue();
			request(2L).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("PLACE_SUGGESTION_BUSY"));
			STUB.releaseBlockedRequest();
			first.get();
		} finally {
			STUB.releaseBlockedRequest();
			executor.shutdownNow();
		}
	}

	private org.springframework.test.web.servlet.ResultActions request() throws Exception {
		return request(1L);
	}

	private org.springframework.test.web.servlet.ResultActions request(long memberId) throws Exception {
		return mockMvc.perform(multipart(URL)
			.file(image("chat.png", "image/png", new byte[] {1}))
			.with(loginAs(memberId)));
	}

	private void performSuccessfulRequest() {
		try {
			request().andExpect(status().isOk());
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private MockMultipartFile image(String filename, String contentType, byte[] content) {
		return new MockMultipartFile("image", filename, contentType, content);
	}
}
