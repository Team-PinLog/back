package com.pinlog.pinlogback.global.exception;

public class InvalidCursorException extends BusinessException {

	public InvalidCursorException() {
		super(ErrorCode.INVALID_INPUT);
	}
}
