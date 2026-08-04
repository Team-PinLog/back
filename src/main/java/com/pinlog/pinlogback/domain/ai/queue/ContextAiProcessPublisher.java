package com.pinlog.pinlogback.domain.ai.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.domain.ai.event.ContextAiRequested;

/**
 * 커밋된 Context의 처리 요청을 큐에 발행한다(BD-48).
 *
 * <p><b>모든 실패를 삼킨다.</b> 인메모리 큐 시절 {@code AiProcessClient}가 그랬던 이유와 같다 —
 * 예외를 올리면 커밋 이후 리스너를 타고 사용자 응답을 오류로 만드는데, Core는 이미 커밋되어
 * 정상이다. 발행이 실패해도 {@code PENDING}이 남아 재스캔이 같은 Context를 다시 집는다.
 *
 * <p>동기 예외까지 잡는 이유: 브로커가 죽어 있으면 {@code send()}가 메타데이터를 기다리다
 * {@code max.block.ms}(1s) 초과로 <b>호출 스레드에서</b> 던진다. 이 클래스는 요청 스레드에서
 * 불리므로 그 예외가 곧 사용자 오류 응답이 된다.
 *
 * <p>키가 {@code contextId}인 것은 같은 Context의 메시지를 같은 파티션에 몰기 위한 것이다.
 * 지금은 파티션이 1이라 효과가 없지만, 키 없는 발행은 파티션을 늘리는 순간 순서가 흩어진다.
 */
@Component
public class ContextAiProcessPublisher {

	private static final Logger log = LoggerFactory.getLogger(ContextAiProcessPublisher.class);

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final AiQueueProperties properties;

	public ContextAiProcessPublisher(KafkaTemplate<String, String> kafkaTemplate, AiQueueProperties properties) {
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
	}

	public void publish(ContextAiRequested event) {
		ContextAiProcessMessage message =
			new ContextAiProcessMessage(event.contextId(), event.memberId(), event.recordId());
		try {
			kafkaTemplate.send(properties.topic(), Long.toString(event.contextId()), message.toJson())
				.whenComplete((result, failure) -> {
					if (failure != null) {
						log.warn("AI process 메시지 발행 실패(브로커 응답 대기 중): contextId={}, cause={}",
							event.contextId(), failure.toString());
					}
				});
		} catch (RuntimeException e) {
			log.warn("AI process 메시지 발행 실패: contextId={}, cause={}", event.contextId(), e.toString());
		}
	}
}
