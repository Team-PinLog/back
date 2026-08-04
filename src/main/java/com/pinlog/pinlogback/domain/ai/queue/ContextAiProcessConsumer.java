package com.pinlog.pinlogback.domain.ai.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.domain.ai.client.AiProcessClient;
import com.pinlog.pinlogback.domain.ai.exception.AiProcessFatalException;
import com.pinlog.pinlogback.domain.ai.service.AiRescanCandidateService;
import com.pinlog.pinlogback.domain.ai.service.ContextProcessRequestAssembler;

/**
 * 큐에서 처리 요청을 꺼내 FastAPI를 호출한다(BD-48). 인메모리 큐 시절 {@code @Async} 리스너가
 * 하던 일의 소비 측 절반이며, <b>FastAPI와의 계약은 그대로다</b> — 조립기도 클라이언트도 재스캔이
 * 쓰는 것과 같은 것을 쓴다.
 *
 * <p>본문을 메시지에서 읽지 않고 조립기로 다시 읽는 이유는 이벤트 시절과 같다
 * ({@code docs/ai/spec/ai-integration.md} 4.2·4.4). 조립기가 비면 그 Context는 소비 시점에 이미
 * 삭제·교체된 것이므로 호출을 생략한다 — 그것이 삭제 확인 그 자체다.
 *
 * <p><b>여기서는 실패를 삼키지 않고 던진다.</b> 인메모리 큐에서는 던져도 아무것도 복구되지
 * 않았지만, 여기서 던지는 것은 재시도 체인의 신호다 — {@code @RetryableTopic}이 백오프가 다른
 * 재시도 토픽으로 옮겨 다시 전달하고, 소진되면 DLT로 격리한다. 단 {@code AiProcessFatalException}
 * (4xx·역직렬화 실패)은 체인을 태우지 않고 DLT로 직행한다 — 같은 메시지는 몇 번을 보내도 같다.
 */
@Component
public class ContextAiProcessConsumer {

	private static final Logger log = LoggerFactory.getLogger(ContextAiProcessConsumer.class);

	private static final String PENDING = "PENDING";

	private final ContextProcessRequestAssembler assembler;
	private final AiProcessClient client;
	private final AiRescanCandidateService candidates;

	public ContextAiProcessConsumer(ContextProcessRequestAssembler assembler, AiProcessClient client,
		AiRescanCandidateService candidates) {
		this.assembler = assembler;
		this.client = client;
		this.candidates = candidates;
	}

	/**
	 * 재시도 값은 {@code pinlog.ai.queue.*}가 정본이다({@link AiQueueProperties}). 애노테이션이
	 * record 대신 플레이스홀더를 읽는 것은 애노테이션 속성에 Bean을 주입할 수 없다는 제약 때문이다.
	 *
	 * <p>{@code traversingCauses}를 켠 이유: 컨테이너가 리스너 예외를
	 * {@code ListenerExecutionFailedException}으로 감싸는 경우가 있어, 원인 사슬을 타고 내려가야
	 * fatal 분류가 우리 예외를 찾는다.
	 */
	@RetryableTopic(
		attempts = "${pinlog.ai.queue.retry-attempts}",
		backOff = @BackOff(
			delayString = "${pinlog.ai.queue.retry-initial-delay-ms}",
			multiplierString = "${pinlog.ai.queue.retry-multiplier}"),
		exclude = AiProcessFatalException.class,
		traversingCauses = "true")
	@KafkaListener(topics = "${pinlog.ai.queue.topic}", groupId = "${pinlog.ai.queue.group}")
	public void consume(String payload) {
		ContextAiProcessMessage message = parse(payload);
		if (!stillWaiting(message.contextId())) {
			return;
		}
		assembler.assemble(message.contextId()).ifPresentOrElse(
			client::processOrThrow,
			() -> log.debug("이미 삭제된 Context라 소비를 생략한다: contextId={}", message.contextId()));
	}

	/**
	 * 멱등 가드. at-least-once 전달에서 중복은 전제이고, 걸러 내는 근거는 브로커가 아니라 진실의
	 * 원본인 {@code ai.context_ai_state}다 — 어느 한쪽 상태라도 {@code PENDING}이면 힌트가 아직
	 * 유효하고, 둘 다 지났으면(PROCESSING·DONE·FAILED·CANCELLED) 보낼 이유가 사라진 것이다.
	 * 상태 행이 없으면 보내지 않는다 — 행 없이 도착한 메시지는 이미 정리된 Context다.
	 */
	private boolean stillWaiting(long contextId) {
		boolean waiting = candidates.findCurrentState(contextId)
			.map(state -> PENDING.equals(state.embeddingStatus()) || PENDING.equals(state.keywordStatus()))
			.orElse(false);
		if (!waiting) {
			log.debug("이미 처리 단계를 지났거나 상태 행이 없는 Context라 소비를 생략한다: contextId={}", contextId);
		}
		return waiting;
	}

	/**
	 * DLQ는 쌓이기만 하면 보이지 않는다(back#129 논의의 관측 우려). ERROR 레벨 + 원문 페이로드가
	 * 관측의 최소선이고, 지표 노출은 {@code /metrics} 승인(infra {@code docs/ai-serving.md} 검증 7)
	 * 뒤의 후속이다. 여기 격리된 Context도 상태는 {@code PENDING}으로 남아 있으므로 재스캔·Finalizer가
	 * 최종 처분(재시도 소진 시 FAILED 종결)을 맡는다 — DLT는 증거 보존이지 별도 복구 경로가 아니다.
	 */
	@DltHandler
	public void deadLetter(String payload,
		@Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String reason) {
		log.error("AI process 메시지가 재시도 소진·영구 실패로 DLT에 격리됐다: payload={}, reason={}",
			payload, reason);
	}

	/** 형식이 틀린 메시지는 몇 번을 다시 읽어도 같다 — 재시도 없이 DLT로 보낸다. */
	private ContextAiProcessMessage parse(String payload) {
		try {
			return ContextAiProcessMessage.fromJson(payload);
		} catch (RuntimeException e) {
			throw new AiProcessFatalException("큐 메시지를 역직렬화하지 못했다", e);
		}
	}
}
