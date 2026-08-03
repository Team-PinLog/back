package com.pinlog.pinlogback.domain.follow.dto;

import java.time.Instant;

import com.pinlog.pinlogback.global.response.CursorPage;

/**
 * {@code collectionSize}를 준 팔로우 목록의 항목(API 명세 9.2, S15P11A705-244).
 * {@link FollowResponse}에 그 책장 Collection의 <b>첫 페이지</b>가 더해진 형태다 —
 * {@code collections.nextCursor}는 기존 {@code GET /follows/{followId}/collections}(9.3)에
 * 그대로 넣어 이어받는다.
 *
 * <p>{@code collectionSize}가 없는 호출은 이 DTO가 아니라 {@link FollowResponse}로 나간다.
 * 그래서 {@code collections}는 항상 채워져 있고, "필드 없음"을 직렬화 옵션으로 흉내 내지 않는다.
 */
public record FollowWithCollectionsResponse(
	Long followId,
	String alias,
	Instant createdAt,
	CursorPage<FollowedCollectionResponse> collections
) {
}
