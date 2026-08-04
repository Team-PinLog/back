/**
 * Context→AI 요청을 나르는 Kafka 큐(BD-48).
 *
 * <p>커밋 후 리스너가 발행하고({@code ContextAiProcessPublisher}), back 내부 컨슈머가 소비해
 * 기존과 동일한 FastAPI 호출을 수행한다({@code ContextAiProcessConsumer}) — FastAPI와의 계약은
 * 바뀌지 않는다. 실패는 재시도 토픽 체인을 거쳐 DLT로 격리되고, 그와 별개로
 * {@code ai.context_ai_state}가 진실의 원본이라는 구조(BD-17의 핵심)와 재스캔 안전망은 유지된다.
 */
package com.pinlog.pinlogback.domain.ai.queue;
