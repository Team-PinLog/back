package com.pinlog.pinlogback.domain.record.dto;

import java.time.Instant;

import com.pinlog.pinlogback.domain.record.entity.Context;

/**
 * contexts 배열 요소(API 명세 11.2). createdAt은 최초 작성 시각(origin_created_at)이다 —
 * 수정으로 contextId가 바뀌어도 승계되어 변하지 않는다.
 */
public record ContextResponse(Long contextId, String body, Instant createdAt) {

	public static ContextResponse from(Context context) {
		return new ContextResponse(context.getId(), context.getBody(), context.getOriginCreatedAt());
	}
}
