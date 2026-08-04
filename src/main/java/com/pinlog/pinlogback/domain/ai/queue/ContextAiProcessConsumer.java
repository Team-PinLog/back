package com.pinlog.pinlogback.domain.ai.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.domain.ai.client.AiProcessClient;
import com.pinlog.pinlogback.domain.ai.service.ContextProcessRequestAssembler;

/**
 * 큐에서 처리 요청을 꺼내 FastAPI를 호출한다(BD-48). 인메모리 큐 시절 {@code @Async} 리스너가
 * 하던 일의 소비 측 절반이며, <b>FastAPI와의 계약은 그대로다</b> — 조립기도 클라이언트도 재스캔이
 * 쓰는 것과 같은 것을 쓴다.
 *
 * <p>본문을 메시지에서 읽지 않고 조립기로 다시 읽는 이유는 이벤트 시절과 같다
 * ({@code docs/ai/spec/ai-integration.md} 4.2·4.4). 조립기가 비면 그 Context는 소비 시점에 이미
 * 삭제·교체된 것이므로 호출을 생략한다 — 그것이 삭제 확인 그 자체다.
 */
@Component
public class ContextAiProcessConsumer {

	private static final Logger log = LoggerFactory.getLogger(ContextAiProcessConsumer.class);

	private final ContextProcessRequestAssembler assembler;
	private final AiProcessClient client;

	public ContextAiProcessConsumer(ContextProcessRequestAssembler assembler, AiProcessClient client) {
		this.assembler = assembler;
		this.client = client;
	}

	@KafkaListener(topics = "${pinlog.ai.queue.topic}", groupId = "${pinlog.ai.queue.group}")
	public void consume(String payload) {
		ContextAiProcessMessage message = ContextAiProcessMessage.fromJson(payload);
		assembler.assemble(message.contextId()).ifPresentOrElse(
			client::process,
			() -> log.debug("이미 삭제된 Context라 소비를 생략한다: contextId={}", message.contextId()));
	}
}
