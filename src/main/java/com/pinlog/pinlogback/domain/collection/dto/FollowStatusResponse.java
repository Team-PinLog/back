package com.pinlog.pinlogback.domain.collection.dto;

/**
 * Collection 상세·책장 탐색의 팔로우 상태(API 명세 11.3). 소유자 조회에서는 상태 객체 자체가 null이다.
 */
public record FollowStatusResponse(boolean followed, Long followId, String alias) {
}
