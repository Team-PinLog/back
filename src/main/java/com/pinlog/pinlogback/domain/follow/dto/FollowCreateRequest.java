package com.pinlog.pinlogback.domain.follow.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Follow 생성 요청(API 명세 8.2). collectionId가 공개 진입점이다 — 작성자의 사용자 ID를
 * 프론트가 알 수 없으므로(식별자 은닉, BD-14) 발견한 Collection으로 그 작성자의 책장을 팔로우한다.
 */
public record FollowCreateRequest(@NotNull Long collectionId) {
}
