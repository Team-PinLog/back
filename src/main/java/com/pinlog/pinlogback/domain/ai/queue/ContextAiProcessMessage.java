package com.pinlog.pinlogback.domain.ai.queue;

import tools.jackson.databind.json.JsonMapper;

/**
 * 큐에 실리는 페이로드. {@code ContextAiRequested} 이벤트와 같은 식별자 셋이다 — 본문을 싣지 않는
 * 이유도 같다({@code docs/ai/spec/ai-integration.md} 4.2: 같은 {@code context_id}로 다른 본문을
 * 보내는 것은 계약 위반이므로, 소비 시점에 Core에서 다시 읽는 것만이 그 계약을 구조로 보장한다).
 *
 * <p>직렬화를 Kafka serializer 설정이 아니라 이 record가 갖는 이유: 페이로드 스키마의 정본을
 * 한 파일로 모으기 위해서다. 필드가 바뀌면 여기와 소비자 가드만 보면 된다.
 *
 * @param contextId 처리 대상 Context
 * @param memberId 소유 회원
 * @param recordId 소속 Record
 */
public record ContextAiProcessMessage(long contextId, long memberId, long recordId) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	public String toJson() {
		return JSON.writeValueAsString(this);
	}

	public static ContextAiProcessMessage fromJson(String json) {
		return JSON.readValue(json, ContextAiProcessMessage.class);
	}
}
