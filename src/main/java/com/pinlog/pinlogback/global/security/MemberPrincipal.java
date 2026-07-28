package com.pinlog.pinlogback.global.security;

/**
 * 컨트롤러가 인증 주체를 받는 타입(authentication.md 2).
 *
 * <p>Entity를 principal로 노출하지 않는다. 컨트롤러 경계에서만 쓰고 서비스는 {@code Long memberId}를
 * 받는다 — 개인 API가 사용자 식별자를 query·body·경로로 받지 않는다는 규칙(BD-14)의 짝이다.
 */
public record MemberPrincipal(Long memberId) {
}
