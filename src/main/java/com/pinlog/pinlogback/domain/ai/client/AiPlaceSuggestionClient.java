package com.pinlog.pinlogback.domain.ai.client;

import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import com.pinlog.pinlogback.domain.ai.AiPlaceSuggestionProperties;
import com.pinlog.pinlogback.domain.ai.AiProperties;
import com.pinlog.pinlogback.domain.ai.exception.AiPlaceSuggestionException;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AiPlaceSuggestionClient {

	private static final Logger log = LoggerFactory.getLogger(AiPlaceSuggestionClient.class);
	private static final String PATH = "/internal/v1/place-suggestions";
	private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
	private static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final String BUSY_CODE = "PLACE_SUGGESTION_BUSY";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final String internalSecret;
	private final Semaphore permits;

	public AiPlaceSuggestionClient(
		@Qualifier("aiPlaceSuggestionRestClient") RestClient aiPlaceSuggestionRestClient,
		ObjectMapper objectMapper,
		AiProperties properties,
		AiPlaceSuggestionProperties placeSuggestionProperties
	) {
		this.restClient = aiPlaceSuggestionRestClient;
		this.objectMapper = objectMapper;
		this.internalSecret = properties.internalSecret();
		this.permits = new Semaphore(placeSuggestionProperties.maxConcurrentRequests());
	}

	public AiPlaceSuggestionResponse suggest(MultipartFile image) {
		if (!permits.tryAcquire()) {
			throw AiPlaceSuggestionException.busy();
		}
		String traceId = currentTraceId();
		try {
			MultipartBodyBuilder body = new MultipartBodyBuilder();
			body.part("image", image.getResource())
				.contentType(MediaType.parseMediaType(image.getContentType()));
			AiPlaceSuggestionResponse response = restClient.post()
				.uri(PATH)
				.header(INTERNAL_SECRET_HEADER, internalSecret)
				.header(TRACE_ID_HEADER, traceId)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body.build())
				.retrieve()
				.body(AiPlaceSuggestionResponse.class);
			if (response == null || response.requestId() == null || response.candidates() == null
				|| response.warnings() == null) {
				log.error("AI place suggestion returned an invalid success body: traceId={}", traceId);
				throw AiPlaceSuggestionException.upstreamError();
			}
			return response;
		} catch (RestClientResponseException failure) {
			throw translate(failure, traceId);
		} catch (ResourceAccessException failure) {
			if (hasCause(failure, SocketTimeoutException.class)) {
				log.warn("AI place suggestion timed out: traceId={}", traceId);
				throw AiPlaceSuggestionException.timeout();
			}
			log.warn("AI place suggestion connection failed: traceId={}, cause={}",
				traceId, failure.getClass().getSimpleName());
			throw AiPlaceSuggestionException.unavailable();
		} catch (AiPlaceSuggestionException failure) {
			throw failure;
		} catch (RestClientException failure) {
			if (hasCause(failure, SocketTimeoutException.class)) {
				log.warn("AI place suggestion timed out: traceId={}", traceId);
				throw AiPlaceSuggestionException.timeout();
			}
			log.error("AI place suggestion client failed: traceId={}, cause={}",
				traceId, failure.getClass().getSimpleName());
			throw AiPlaceSuggestionException.unavailable();
		} finally {
			permits.release();
		}
	}

	private AiPlaceSuggestionException translate(RestClientResponseException failure, String traceId) {
		int status = failure.getStatusCode().value();
		log.warn("AI place suggestion rejected the request: status={}, traceId={}", status, traceId);
		return switch (status) {
			case 400, 422 -> AiPlaceSuggestionException.invalidImage();
			case 413 -> AiPlaceSuggestionException.imageTooLarge();
			case 415 -> AiPlaceSuggestionException.unsupportedMediaType();
			case 500 -> AiPlaceSuggestionException.internalError();
			case 502 -> AiPlaceSuggestionException.upstreamError();
			case 503 -> isBusy(failure.getResponseBodyAsString())
				? AiPlaceSuggestionException.busy() : AiPlaceSuggestionException.unavailable();
			case 504 -> AiPlaceSuggestionException.timeout();
			default -> AiPlaceSuggestionException.upstreamError();
		};
	}

	private boolean isBusy(String body) {
		if (body.isBlank()) {
			return false;
		}
		try {
			JsonNode root = objectMapper.readTree(body);
			JsonNode detail = root.path("detail");
			return BUSY_CODE.equals(detail.asString()) || BUSY_CODE.equals(detail.path("code").asString())
				|| BUSY_CODE.equals(root.path("code").asString());
		} catch (JacksonException ignored) {
			return false;
		}
	}

	private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
		Throwable current = failure;
		while (current != null) {
			if (type.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private String currentTraceId() {
		String traceId = MDC.get(TraceIdFilter.TRACE_ID);
		return traceId != null ? traceId : UUID.randomUUID().toString();
	}
}
