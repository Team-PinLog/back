package com.pinlog.pinlogback.global.exception;

/**
 * 리소스 없음 또는 자원 접근 권한 실패 — 404(API 명세 1.2).
 * 타인의 자원 접근을 403으로 알려주면 존재 여부가 노출되므로 같은 404로 은닉한다.
 */
public class ResourceNotFoundException extends BusinessException {

	public ResourceNotFoundException() {
		super(ErrorCode.RESOURCE_NOT_FOUND);
	}
}
