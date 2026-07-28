package com.pinlog.pinlogback.domain.follow.dto;

/**
 * 별칭 수정·제거 요청(API 명세 8.3). null은 제거를 의미하므로 필수 검증을 걸지 않는다.
 * 정규화(strip, 빈 값 → null)와 길이 검증은 서비스가 담당한다.
 */
public record FollowAliasUpdateRequest(String alias) {
}
