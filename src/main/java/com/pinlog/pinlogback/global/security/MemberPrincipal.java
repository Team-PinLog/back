package com.pinlog.pinlogback.global.security;

/**
 * 컨트롤러가 인증 주체를 받는 타입(인증 스텁 계약, back#28).
 *
 * <p>컨트롤러 경계에서만 쓰고 서비스는 {@code Long memberId}를 받는다(BD-14) — 인증 PR이 오면
 * 리졸버 본문만 실제 인증으로 바뀌고 이 타입과 도메인 코드는 그대로 남는다.
 */
public record MemberPrincipal(Long memberId) {
}
