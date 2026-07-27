package com.pinlog.pinlogback.global.exception;

import org.springframework.http.HttpStatus;

public abstract class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	protected BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	protected BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public HttpStatus getHttpStatus() {
		return errorCode.getHttpStatus();
	}

	public String getCode() {
		return errorCode.getCode();
	}
}
