package com.pinlog.pinlogback.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청 형식입니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 메서드입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
	// 미인증. 타인 소유 자원 접근은 존재를 숨기기 위해 403이 아니라 404를 쓴다(08 §1.2).
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	// CSRF 토큰 누락·불일치 전용이다(08 §1.7).
	FORBIDDEN(HttpStatus.FORBIDDEN, "요청이 거부되었습니다.");

	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getCode() {
		return name();
	}

	public String getMessage() {
		return message;
	}
}
