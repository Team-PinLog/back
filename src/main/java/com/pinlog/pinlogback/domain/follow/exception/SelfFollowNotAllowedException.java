package com.pinlog.pinlogback.domain.follow.exception;

import com.pinlog.pinlogback.global.exception.BusinessException;
import com.pinlog.pinlogback.global.exception.ErrorCode;

/**
 * 자기 자신의 Shelf 팔로우 — 422 도메인 규칙 위반. DB CHECK(ck_follow_self)와 같은 규칙을
 * 요청 검증 단계에서 사람이 읽을 수 있는 코드로 돌려준다.
 */
public class SelfFollowNotAllowedException extends BusinessException {

	public SelfFollowNotAllowedException() {
		super(ErrorCode.SELF_FOLLOW_NOT_ALLOWED);
	}
}
