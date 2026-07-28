package com.pinlog.pinlogback.global.exception;

import com.pinlog.pinlogback.global.response.ErrorResponse;

/**
 * 연쇄 삭제 확인 요구 — 409(API 명세 5.6·5.7). 프론트는 이 Record가 어느 Collection의
 * 마지막인지 알 수 없으므로 서버가 판정해 영향 범위(impact)를 함께 돌려준다. 프론트는 안내 후
 * 강제 삭제 API 또는 Collection 삭제 API로만 실제 삭제를 수행한다.
 */
public class DeleteConfirmationRequiredException extends BusinessException {

	private final transient ErrorResponse.Impact impact;

	public DeleteConfirmationRequiredException(String message, ErrorResponse.Impact impact) {
		super(ErrorCode.DELETE_CONFIRMATION_REQUIRED, message);
		this.impact = impact;
	}

	public ErrorResponse.Impact getImpact() {
		return impact;
	}
}
