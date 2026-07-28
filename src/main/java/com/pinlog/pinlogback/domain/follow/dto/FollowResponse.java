package com.pinlog.pinlogback.domain.follow.dto;

import java.time.Instant;

import com.pinlog.pinlogback.domain.follow.entity.Follow;

/**
 * Follow 응답(API 명세 8.2·9.2). 팔로우 대상의 신원 정보(내부 사용자 ID 등)는 포함하지 않는다.
 */
public record FollowResponse(Long followId, String alias, Instant createdAt) {

	public static FollowResponse from(Follow follow) {
		return new FollowResponse(follow.getId(), follow.getDisplayName(), follow.getCreatedAt());
	}
}
