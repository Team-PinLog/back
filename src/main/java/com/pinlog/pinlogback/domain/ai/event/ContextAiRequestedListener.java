package com.pinlog.pinlogback.domain.ai.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.pinlog.pinlogback.domain.ai.client.AiProcessClient;
import com.pinlog.pinlogback.domain.ai.service.ContextProcessRequestAssembler;

/**
 * Core 커밋 이후에 FastAPI를 호출한다(AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 4장).
 *
 * <p><b>{@code AFTER_COMMIT}이 이 클래스의 존재 이유다.</b> 트랜잭션 안에서 호출하면 FastAPI가
 * 아직 커밋되지 않은 {@code ai.context_ai_state}를 조회해 처리 대상을 찾지 못한다. 워커는 별
 * 프로세스이므로 우리 트랜잭션의 미커밋 스냅샷을 볼 수 없다 — 202를 받고도 아무 일도 일어나지
 * 않는다. 외부 호출 지연만큼 DB 커넥션과 행 잠금이 유지되는 문제도 함께 사라진다.
 *
 * <p>롤백된 트랜잭션에서는 이 리스너가 아예 실행되지 않으므로, 저장되지 않은 Context로 호출이
 * 나가는 경로가 없다. 반대 방향도 마찬가지다 — 여기서 무슨 일이 나든 Core는 이미 커밋되어 있어
 * 되돌아가지 않는다.
 *
 * <p>{@code @Async}는 응답 지연을 사용자 요청 시간에서 떼기 위한 것이다. 동기로 두면 FastAPI
 * 응답 시간이 그대로 Record 저장 API의 응답 시간이 된다.
 */
@Component
public class ContextAiRequestedListener {

	private static final Logger log = LoggerFactory.getLogger(ContextAiRequestedListener.class);

	private final ContextProcessRequestAssembler assembler;
	private final AiProcessClient client;

	public ContextAiRequestedListener(ContextProcessRequestAssembler assembler, AiProcessClient client) {
		this.assembler = assembler;
		this.client = client;
	}

	@Async("aiCallExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(ContextAiRequested event) {
		try {
			assembler.assemble(event).ifPresentOrElse(
				client::process,
				() -> log.debug("이미 삭제된 Context라 AI process 호출을 생략한다: contextId={}", event.contextId()));
		} catch (RuntimeException e) {
			// 조립 단계(DB 조회)의 실패까지 삼킨다. 여기서 던지면 @Async의 uncaught handler로 갈 뿐
			// 아무 것도 복구되지 않고, PENDING은 이미 커밋되어 있어 재스캔이 같은 Context를 다시 집는다.
			log.warn("AI process 요청 조립 실패: contextId={}, cause={}", event.contextId(), e.toString());
		}
	}
}
