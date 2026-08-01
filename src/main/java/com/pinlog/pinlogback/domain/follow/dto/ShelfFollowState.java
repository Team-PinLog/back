package com.pinlog.pinlogback.domain.follow.dto;

import com.pinlog.pinlogback.domain.follow.entity.Follow;

/**
 * 책장 탐색 응답의 팔로우 상태(API 명세 8.1). <b>요청자 기준</b>이며 작성자의 팔로워 수 같은
 * 집계가 아니다 — 프론트가 팔로우 버튼을 어떤 상태로 그릴지 정하는 데만 쓴다.
 *
 * <p>{@code alias}는 그것을 지정한 본인에게만 보인다(접근 권한표 12장). 요청자의 Follow 행에서
 * 읽으므로 타인의 별칭이 실릴 경로가 없다.
 */
public record ShelfFollowState(boolean followed, Long followId, String alias) {

	public static ShelfFollowState notFollowed() {
		return new ShelfFollowState(false, null, null);
	}

	public static ShelfFollowState from(Follow follow) {
		return new ShelfFollowState(true, follow.getId(), follow.getDisplayName());
	}
}
