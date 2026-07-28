package com.pinlog.pinlogback.domain.record.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.record.entity.Context;

/**
 * Context 추가·수정 응답(API 명세 5.4·5.5). keywords는 AI 파트가 비동기로 채우므로
 * 생성 직후에는 빈 배열이 정상 상태다(명세 1.3).
 */
public record ContextMutationResponse(Long contextId, String body, Instant createdAt, List<String> keywords) {

	public static ContextMutationResponse from(Context context) {
		return new ContextMutationResponse(
			context.getId(), context.getBody(), context.getOriginCreatedAt(), List.of());
	}
}
