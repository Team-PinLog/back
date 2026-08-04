package com.pinlog.pinlogback.domain.ai.exception;

/**
 * FastAPI {@code process} 호출의 영구 실패 — 4xx(요청 형식·시크릿 문제)와 역직렬화 실패. 같은
 * 메시지를 몇 번을 다시 보내도 결과가 같으므로 재시도 체인을 타지 않고 DLT로 직행한다(BD-48).
 * 재시도로 시간을 끌면 사람이 봐야 할 문제의 발견만 늦어진다.
 */
public class AiProcessFatalException extends RuntimeException {

	public AiProcessFatalException(String message, Throwable cause) {
		super(message, cause);
	}
}
