package com.pinlog.pinlogback.domain.auth.exception;

/**
 * 공급자 연결 해제가 실패했다(BD-48).
 *
 * <p><b>삼키면 안 된다.</b> 해제와 탈퇴는 원자적이어야 하고, 해제되지 않은 채 회원을 지우면
 * {@code social_account} 마스킹이 공급자 식별자를 파기해 <b>되살릴 방법이 없다</b> — 카카오 약관이
 * 요구하는 해제를 영구히 이행할 수 없게 된다.
 */
public class SocialUnlinkException extends RuntimeException {

	public SocialUnlinkException(String message, Throwable cause) {
		super(message, cause);
	}
}
