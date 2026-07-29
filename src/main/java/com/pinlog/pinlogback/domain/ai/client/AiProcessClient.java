package com.pinlog.pinlogback.domain.ai.client;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.pinlog.pinlogback.domain.ai.AiProperties;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

/**
 * {@code POST /internal/v1/context/process} 호출. AI 파트 소유 명세
 * {@code docs/ai/spec/ai-integration.md} 5·6장을 그대로 따른다.
 *
 * <p><b>Fire-and-forget이다.</b> {@code 202 Accepted}는 접수만 뜻하고 처리 완료가 아니다. 완료
 * 통보용 웹훅·콜백이 없으므로 이 클래스는 응답 본문을 읽지 않는다. 정합성의 근거는 이 호출이
 * 아니라 DB에 영속된 {@code ai.context_ai_state}다 — 이 호출은 "지금 처리하면 조금 빨라지는 힌트"다.
 *
 * <p><b>모든 실패를 삼킨다.</b> 예외를 밖으로 던지면 호출자(커밋 이후 리스너)를 타고 올라가
 * 사용자 응답을 오류로 만들 수 있는데, Core 데이터는 이미 커밋되어 정상이므로 그것은 거짓말이다.
 * 상태를 {@code FAILED}로 바꾸지도, {@code retry_count}를 올리지도 않는다 — 호출 실패는 FastAPI
 * 내부 작업의 실패가 아니고, 두 주체가 같은 사유로 FAILED를 각각 기록하면 원인 추적이 불가능해진다.
 * {@code PENDING}이 남아 있으므로 재스캔이 같은 Context를 다시 집는다.
 */
@Component
public class AiProcessClient {

	private static final Logger log = LoggerFactory.getLogger(AiProcessClient.class);

	private static final String PATH = "/internal/v1/context/process";
	/** ai 레포 {@code app/core/security.py::INTERNAL_SECRET_HEADER}가 실행 가능한 계약의 원본이다. */
	private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final RestClient restClient;
	private final String internalSecret;

	public AiProcessClient(RestClient aiProcessRestClient, AiProperties properties) {
		this.restClient = aiProcessRestClient;
		this.internalSecret = properties.internalSecret();
	}

	/** 호출 결과를 반환하지 않는다. 호출자가 분기할 수 있으면 그 분기가 곧 Core 트랜잭션 결과에 스며든다. */
	public void process(ContextProcessRequest request) {
		String requestId = currentRequestId();
		try {
			restClient.post()
				.uri(PATH)
				.header(INTERNAL_SECRET_HEADER, internalSecret)
				.header(REQUEST_ID_HEADER, requestId)
				.body(request)
				.retrieve()
				.toBodilessEntity();
			log.debug("AI process 접수됨: contextId={}, requestId={}", request.contextId(), requestId);
		} catch (RestClientResponseException e) {
			// 4xx는 요청 payload 형식 문제일 가능성이 있어 사람이 봐야 한다. 5xx는 상대 장애이므로
			// 재스캔이 흡수한다. 응답 본문은 남기지 않는다 — 내부 API라도 로그로 새어 나갈 이유가 없다.
			logByStatus(e, request, requestId);
		} catch (RuntimeException e) {
			log.warn("AI process 호출 실패(연결·타임아웃): contextId={}, requestId={}, cause={}",
				request.contextId(), requestId, e.toString());
		}
	}

	private void logByStatus(RestClientResponseException failure, ContextProcessRequest request, String requestId) {
		if (failure.getStatusCode().is4xxClientError()) {
			log.error("AI process 호출이 {}로 거절됐다(요청 형식 문제 가능성): contextId={}, requestId={}",
				failure.getStatusCode().value(), request.contextId(), requestId);
			return;
		}
		log.warn("AI process 호출이 {}를 받았다: contextId={}, requestId={}",
			failure.getStatusCode().value(), request.contextId(), requestId);
	}

	/**
	 * 커밋 이후 별도 스레드에서 도므로 MDC의 traceId가 보통 비어 있다. 그때는 새로 만든다 —
	 * 헤더를 비우면 FastAPI 로그와 우리 로그를 이을 값 자체가 사라진다.
	 */
	private String currentRequestId() {
		String traceId = MDC.get(TraceIdFilter.TRACE_ID);
		return traceId != null ? traceId : UUID.randomUUID().toString();
	}
}
