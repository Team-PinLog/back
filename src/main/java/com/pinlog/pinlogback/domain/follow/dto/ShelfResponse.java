package com.pinlog.pinlogback.domain.follow.dto;

import com.pinlog.pinlogback.global.response.CursorPage;

/**
 * 작성자 공개 책장 탐색 응답(API 명세 8.1).
 *
 * <p>{@code sourceCollectionId}를 되돌려주는 이유는 진입점을 응답에서 다시 확인할 수 있어야
 * 하기 때문이다 — 프론트가 이 값으로 {@code POST /follows}를 부른다(8.2).
 *
 * <p>목록만 내보내지 않고 {@code follow}를 함께 싣는 것이 §9.3과의 차이다. 팔로우 전 상태에서
 * 진입하는 화면이라, 목록과 팔로우 버튼 상태를 한 번에 그릴 수 있어야 왕복이 줄어든다.
 *
 * @param sourceCollectionId 탐색 진입점이 된 Collection id
 * @param follow 요청자 기준 팔로우 상태
 * @param collections 작성자의 발행 Collection 커서 페이지
 */
public record ShelfResponse(
	Long sourceCollectionId,
	ShelfFollowState follow,
	CursorPage<FollowedCollectionResponse> collections
) {
}
