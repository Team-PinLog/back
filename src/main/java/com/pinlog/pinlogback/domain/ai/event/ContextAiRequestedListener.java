package com.pinlog.pinlogback.domain.ai.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.pinlog.pinlogback.domain.ai.queue.ContextAiProcessPublisher;

/**
 * Core 커밋 이후에 처리 요청을 큐에 발행한다(BD-48).
 *
 * <p><b>{@code AFTER_COMMIT}이 이 클래스의 존재 이유다.</b> 커밋 전에 발행하면 컨슈머가 아직
 * 커밋되지 않은 {@code ai.context_ai_state}를 조회해 처리 대상을 찾지 못한다 — 컨슈머와 FastAPI
 * 워커는 별 프로세스이므로 우리 트랜잭션의 미커밋 스냅샷을 볼 수 없다. 롤백된 트랜잭션에서는
 * 이 리스너가 아예 실행되지 않으므로, 저장되지 않은 Context의 메시지가 큐에 실리는 경로가 없다.
 *
 * <p>인메모리 큐 시절의 {@code @Async}는 여기 없다. FastAPI 응답을 기다리던 HTTP 호출과 달리
 * {@code send()}는 프로듀서 버퍼에 넣고 곧바로 돌아오므로 떼어 낼 지연이 없고, 브로커 장애로
 * 블록되는 최악의 경우도 {@code max.block.ms}(1s)가 끊는다. 발행 실패는
 * {@link ContextAiProcessPublisher}가 삼킨다 — {@code PENDING}이 이미 커밋되어 있어 재스캔이
 * 같은 Context를 다시 집는다.
 */
@Component
public class ContextAiRequestedListener {

	private final ContextAiProcessPublisher publisher;

	public ContextAiRequestedListener(ContextAiProcessPublisher publisher) {
		this.publisher = publisher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(ContextAiRequested event) {
		publisher.publish(event);
	}
}
