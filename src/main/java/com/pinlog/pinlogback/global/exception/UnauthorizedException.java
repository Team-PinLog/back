package com.pinlog.pinlogback.global.exception;

/**
 * 인증 실패 — 쿠키 없음·만료·서명 불일치, 회전 전 Refresh 재사용. 401이다.
 *
 * <p>자원 접근 권한 실패는 이 예외가 아니다. 존재 여부를 노출하지 않기 위해 404를 쓴다
 * (API 명세 1.2, BD-13). {@code 403}은 CSRF 실패 전용이다(08 §1.7).
 */
public class UnauthorizedException extends BusinessException {

	public UnauthorizedException() {
		super(ErrorCode.UNAUTHORIZED);
	}
}
