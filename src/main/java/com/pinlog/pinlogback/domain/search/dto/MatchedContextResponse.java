package com.pinlog.pinlogback.domain.search.dto;

import java.time.Instant;

import com.pinlog.pinlogback.domain.record.entity.Context;

/**
 * Record에서 질의와 가장 잘 맞은 Context(API 명세 6.1, AI 설계 9.4).
 *
 * <p><b>본문은 FastAPI가 주지 않는다.</b> 검색 응답에는 {@code contextId}만 오고, 그 id로 Core에서
 * 본문을 조회해 여기 채운다 — {@code ai} 스키마에 본문 사본을 두지 않기 때문이다.
 *
 * <p>{@code contextId}를 함께 내리는 이유는 검색 카드에서 곧바로
 * {@code PATCH·DELETE /records/{recordId}/contexts/{contextId}}로 이어지기 때문이다(명세 6.1
 * "검색 결과 카드 요구사항"). 검색은 본인 데이터 전용이므로 본문을 실어도 된다(응답 조립 명세 6.3).
 *
 * @param createdAt 최초 작성 시각({@code origin_created_at})이다. 수정으로 {@code contextId}가 바뀌어도
 *     승계되므로 사용자가 보는 날짜가 흔들리지 않는다(BD-25)
 */
public record MatchedContextResponse(Long contextId, String body, Instant createdAt) {

	public static MatchedContextResponse from(Context context) {
		return new MatchedContextResponse(context.getId(), context.getBody(), context.getOriginCreatedAt());
	}
}
