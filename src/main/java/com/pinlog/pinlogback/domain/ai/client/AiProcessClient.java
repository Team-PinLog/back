package com.pinlog.pinlogback.domain.ai.client;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.pinlog.pinlogback.domain.ai.AiProperties;
import com.pinlog.pinlogback.domain.ai.exception.AiProcessFatalException;
import com.pinlog.pinlogback.domain.ai.exception.AiProcessRetryableException;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

/**
 * {@code POST /internal/v1/context/process} 호출. AI 파트 소유 명세
 * {@code docs/ai/spec/ai-integration.md} 5·6장을 그대로 따른다.
 *
 * <p><b>Fire-and-forget이다.</b> {@code 202 Accepted}는 접수만 뜻하고 처리 완료가 아니다. 완료
 * 통보용 웹훅·콜백이 없으므로 이 클래스는 응답 본문을 읽지 않는다. 정합성의 근거는 이 호출이
 * 아니라 DB에 영속된 {@code ai.context_ai_state}다 — 이 호출은 "지금 처리하면 조금 빨라지는 힌트"다.
 *
 * <p>실패의 취급이 호출자마다 다르므로 진입점이 둘이다. {@link #process}는 <b>모든 실패를
 * 삼킨다</b> — 재스캔 경로에서 예외는 아무것도 복구하지 못하고, {@code PENDING}이 남아 다음
 * 회차가 같은 Context를 다시 집는다. {@link #processOrThrow}는 실패를 분류해 던진다 — 큐 소비
 * 경로에서는 던지는 것이 곧 재시도 체인·DLT 격리의 신호다(BD-48). 어느 쪽도 상태를
 * {@code FAILED}로 바꾸거나 {@code retry_count}를 올리지 않는다 — 호출 실패는 FastAPI 내부
 * 작업의 실패가 아니고, 두 주체가 같은 사유로 FAILED를 각각 기록하면 원인 추적이 불가능해진다.
 */
@Component
public class AiProcessClient {

	private static final Logger log = LoggerFactory.getLogger(AiProcessClient.class);

	private static final String PATH = "/internal/v1/context/process";
	/** ai 레포 {@code app/core/security.py::INTERNAL_SECRET_HEADER}가 실행 가능한 계약의 원본이다. */
	private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";
	private static final String PROD_PROFILE = "prod";

	private final RestClient restClient;
	private final String internalSecret;

	public AiProcessClient(@Qualifier("aiProcessRestClient") RestClient aiProcessRestClient,
		AiProperties properties, Environment environment) {
		this.restClient = aiProcessRestClient;
		this.internalSecret = requireSecret(properties.internalSecret(), environment);
	}

	/**
	 * 시크릿이 없을 때의 동작을 {@link com.pinlog.pinlogback.global.security.token.JwtKeyProvider}와
	 * 같은 기준으로 가른다 — 운영은 기동 실패, 그 외는 경고.
	 *
	 * <p>이 검사가 필요한 이유는 <b>시크릿 부재의 실패 모드가 완전히 무음</b>이기 때문이다. 값이 비면
	 * FastAPI가 401을 주고, 이 클래스는 모든 실패를 삼키며, 상태는 {@code PENDING}으로 남고, 재스캔은
	 * 아직 없다. 결과는 "임베딩이 하나도 생기지 않는데 로그 말고는 신호가 없는" 상태다 — 이 연동이
	 * 애초에 없앴어야 할 바로 그 상태와 같다.
	 */
	private static String requireSecret(String secret, Environment environment) {
		if (secret != null && !secret.isBlank()) {
			return secret;
		}
		if (environment.matchesProfiles(PROD_PROFILE)) {
			throw new IllegalStateException(
				"운영 프로파일에는 pinlog.ai.internal-secret(PINLOG_AI_INTERNAL_SECRET)이 필요하다. "
					+ "값이 없으면 모든 AI 호출이 401로 거절되는데 이 클라이언트는 실패를 삼키므로 "
					+ "임베딩이 전혀 생성되지 않는 것을 아무도 알 수 없다");
		}
		log.warn("pinlog.ai.internal-secret이 비어 있다. AI 호출은 401로 거절되며 실패는 삼켜진다 — "
			+ "임베딩·Keyword가 생성되지 않는다.");
		return "";
	}

	/** 호출 결과를 반환하지 않는다. 호출자가 분기할 수 있으면 그 분기가 곧 Core 트랜잭션 결과에 스며든다. */
	public void process(ContextProcessRequest request) {
		try {
			processOrThrow(request);
		} catch (AiProcessRetryableException | AiProcessFatalException e) {
			// 로그는 processOrThrow가 이미 남겼다. 재스캔 경로에서 실패는 여기서 끝난다 —
			// PENDING이 남아 있으므로 다음 회차가 같은 Context를 다시 집는다.
		}
	}

	/**
	 * 실패를 분류해 던진다. 5xx·연결 계열은 {@link AiProcessRetryableException}(다시 보내면 성공할
	 * 수 있다), 4xx는 {@link AiProcessFatalException}(요청 자체의 문제라 몇 번을 보내도 같다).
	 * 401·403도 fatal이다 — 시크릿·헤더 설정이 고쳐지기 전에는 재시도가 전부 같은 답을 받는다.
	 */
	public void processOrThrow(ContextProcessRequest request) {
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
			// 4xx는 요청 payload 형식 문제일 가능성이 있어 사람이 봐야 한다. 응답 본문은 남기지
			// 않는다 — 내부 API라도 로그로 새어 나갈 이유가 없다.
			logByStatus(e, request, requestId);
			if (e.getStatusCode().is4xxClientError()) {
				throw new AiProcessFatalException(
					"AI process 호출이 " + e.getStatusCode().value() + "로 거절됐다: contextId=" + request.contextId(), e);
			}
			throw new AiProcessRetryableException(
				"AI process 호출이 " + e.getStatusCode().value() + "를 받았다: contextId=" + request.contextId(), e);
		} catch (RuntimeException e) {
			log.warn("AI process 호출 실패(연결·타임아웃): contextId={}, requestId={}, cause={}",
				request.contextId(), requestId, e.toString());
			throw new AiProcessRetryableException(
				"AI process 호출이 연결 단계에서 실패했다: contextId=" + request.contextId(), e);
		}
	}

	private void logByStatus(RestClientResponseException failure, ContextProcessRequest request, String requestId) {
		int status = failure.getStatusCode().value();
		// 401·403을 나머지 4xx와 분리한다. 이 둘은 요청 형식이 아니라 시크릿·헤더 설정 문제이고,
		// 실제로 가장 자주 나는 운영 오류다. 같은 문장으로 뭉뚱그리면 로그가 엉뚱한 곳을 가리킨다.
		if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
			log.error("AI process 호출이 {}로 거절됐다(시크릿·헤더 설정 문제): "
					+ "pinlog.ai.internal-secret과 ai 레포의 INTERNAL_SHARED_SECRET이 같은 값인지, "
					+ "헤더 이름이 {}인지 확인하라. contextId={}, requestId={}",
				status, INTERNAL_SECRET_HEADER, request.contextId(), requestId);
			return;
		}
		if (failure.getStatusCode().is4xxClientError()) {
			log.error("AI process 호출이 {}로 거절됐다(요청 형식 문제 가능성): contextId={}, requestId={}",
				status, request.contextId(), requestId);
			return;
		}
		log.warn("AI process 호출이 {}를 받았다: contextId={}, requestId={}",
			status, request.contextId(), requestId);
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
