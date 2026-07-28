package com.pinlog.pinlogback.global.exception;

/**
 * Bean Validation으로 표현하기 어려운 요청 형식 위반 — 400(INVALID_INPUT).
 * 예: 지도 bbox 파라미터를 일부만 보낸 경우.
 */
public class InvalidRequestException extends BusinessException {

	public InvalidRequestException(String message) {
		super(ErrorCode.INVALID_INPUT, message);
	}
}
