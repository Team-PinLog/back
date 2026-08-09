package com.pinlog.pinlogback.domain.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestClient;

import com.pinlog.pinlogback.domain.ai.AiPlaceSuggestionProperties;
import com.pinlog.pinlogback.domain.ai.AiProperties;
import com.pinlog.pinlogback.domain.ai.client.AiPlaceSuggestionClient;
import com.pinlog.pinlogback.domain.ai.client.AiPlaceSuggestionResponse;
import com.pinlog.pinlogback.domain.ai.exception.AiPlaceSuggestionException;

import tools.jackson.databind.json.JsonMapper;

class AiPlaceSuggestionClientStubTests {

	private static final String INTERNAL_SECRET = "test-internal-secret";
	private static final FastApiPlaceSuggestionStub STUB = new FastApiPlaceSuggestionStub();

	private final AiPlaceSuggestionClient client = newClient();

	@BeforeEach
	void resetStub() {
		STUB.willRespondWith(FastApiPlaceSuggestionStub.Mode.SUCCESS);
	}

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	@Test
	void sendsMultipartHeadersAndReadsASuccessResponse() {
		AiPlaceSuggestionResponse response = client.suggest(image());

		assertThat(response.requestId()).isEqualTo("req_test");
		assertThat(response.candidates()).hasSize(1);
		assertThat(response.candidates().get(0).extracted().placeName()).isEqualTo("Zootopia Seoul");
		assertThat(response.candidates().get(0).kakaoSearch().items().get(0).kakaoPlaceId())
			.isEqualTo("12345");

		FastApiPlaceSuggestionStub.Received received = STUB.lastCall();
		assertThat(received.internalSecret()).isEqualTo(INTERNAL_SECRET);
		assertThat(received.traceId()).isNotBlank();
		assertThat(received.contentType()).startsWith("multipart/form-data");
		assertThat(new String(received.body(), StandardCharsets.ISO_8859_1))
			.contains("chat.png", "png-content");
	}

	@Test
	void mapsImageRequestErrors() {
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.BAD_REQUEST, "INVALID_IMAGE");
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.TOO_LARGE, "IMAGE_TOO_LARGE");
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.UNSUPPORTED_MEDIA_TYPE,
			"UNSUPPORTED_MEDIA_TYPE");
	}

	@Test
	void mapsInternalAndUpstreamErrorsWithoutExposingTheResponseBody() {
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.UNAUTHORIZED,
			"PLACE_SUGGESTION_UPSTREAM_ERROR");
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.INTERNAL_ERROR, "INTERNAL_ERROR");
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.UPSTREAM_ERROR,
			"PLACE_SUGGESTION_UPSTREAM_ERROR");
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.UNAVAILABLE,
			"PLACE_SUGGESTION_UNAVAILABLE");
	}

	@Test
	void distinguishesBusyFromAServiceOutage() {
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.BUSY, "PLACE_SUGGESTION_BUSY");
	}

	@Test
	void mapsAReadTimeoutToGatewayTimeout() {
		assertModeMapsTo(FastApiPlaceSuggestionStub.Mode.TIMEOUT, "PLACE_SUGGESTION_TIMEOUT");
	}

	private void assertModeMapsTo(FastApiPlaceSuggestionStub.Mode mode, String expectedCode) {
		STUB.willRespondWith(mode);

		assertThatThrownBy(() -> client.suggest(image()))
			.isInstanceOfSatisfying(AiPlaceSuggestionException.class,
				failure -> assertThat(failure.getCode()).isEqualTo(expectedCode));
	}

	private AiPlaceSuggestionClient newClient() {
		Duration connectTimeout = Duration.ofMillis(200);
		Duration readTimeout = Duration.ofMillis(200);
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeout);
		requestFactory.setReadTimeout(readTimeout);
		RestClient restClient = RestClient.builder()
			.baseUrl(STUB.baseUrl())
			.requestFactory(requestFactory)
			.build();
		AiProperties.Timeouts existingTimeouts = new AiProperties.Timeouts(connectTimeout, readTimeout);
		AiProperties properties = new AiProperties(
			STUB.baseUrl(), INTERNAL_SECRET, "test-profile", existingTimeouts, existingTimeouts,
			existingTimeouts);
		AiPlaceSuggestionProperties placeProperties =
			new AiPlaceSuggestionProperties(connectTimeout, readTimeout, 1);
		return new AiPlaceSuggestionClient(
			restClient, JsonMapper.builder().build(), properties, placeProperties);
	}

	private MockMultipartFile image() {
		return new MockMultipartFile(
			"image", "chat.png", "image/png", "png-content".getBytes(StandardCharsets.UTF_8));
	}
}
