package com.pinlog.pinlogback.global.exception;

/**
 * 인증 실패(쿠키 없음·만료 등) — 401. 자원 접근 권한 실패는 404를 쓴다(API 명세 1.2).
 */
public class UnauthorizedException extends BusinessException {

	public UnauthorizedException() {
		super(ErrorCode.UNAUTHORIZED);
	}
}
