package com.pinlog.pinlogback.domain.ai.exception;

/**
 * FastAPI {@code process} 호출의 일시 장애 — 5xx, 연결 거부, 타임아웃. 나중에 다시 보내면 성공할
 * 수 있으므로 재시도 토픽 체인의 대상이다(BD-48). 소진되면 DLT로 격리된다.
 */
public class AiProcessRetryableException extends RuntimeException {

	public AiProcessRetryableException(String message, Throwable cause) {
		super(message, cause);
	}
}
