package com.pinlog.pinlogback.global.exception;

/**
 * 동일 Shelf 중복 팔로우 — 409 상태 충돌. 활성행 부분 유니크(uq_follow_active)와 같은 규칙이다.
 */
public class DuplicateFollowException extends BusinessException {

	public DuplicateFollowException() {
		super(ErrorCode.DUPLICATE_FOLLOW);
	}
}
