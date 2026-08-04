package com.pinlog.pinlogback.domain.auth.exception;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 공급자 연결 해제가 실패했다(BD-48).
 *
 * <p><b>삼키면 안 된다.</b> 해제와 탈퇴는 원자적이어야 하고, 해제되지 않은 채 회원을 지우면
 * {@code social_account} 마스킹이 공급자 식별자를 파기해 <b>되살릴 방법이 없다</b> — 카카오 약관이
 * 요구하는 해제를 영구히 이행할 수 없게 된다.
 *
 * <p><b>일시적인지 아닌지를 함께 나른다.</b> 뭉뚱그리면 둘 중 하나가 망가진다 — 전부 포기하면
 * 공급자의 순간적 장애 하나가 탈퇴를 영구히 막고, 전부 되풀이하면 자격증명 오류처럼 몇 번을 보내도
 * 같은 실패에 사용자를 기다리게 한다.
 */
public class SocialUnlinkException extends RuntimeException {

	/** RFC 6585. 잠시 뒤 다시 보내라는 뜻이므로 되풀이가 의미 있다. */
	private static final int TOO_MANY_REQUESTS = 429;

	private final boolean retryable;

	public SocialUnlinkException(String message, @Nullable Throwable cause, boolean retryable) {
		super(message, cause);
		this.retryable = retryable;
	}

	/**
	 * 호출 실패를 판정해 감싼다.
	 *
	 * <p>응답을 받았으면 상태 코드가 말한다 — 5xx와 429는 공급자 사정이라 되풀이가 의미 있고,
	 * 나머지 4xx는 우리 요청이나 자격증명이 틀린 것이라 되풀이해도 같다. [RFC 7009]는 503에
	 * <i>"토큰이 아직 존재한다고 가정하고 적절한 지연 후 재시도"</i>를 규범으로 둔다.
	 *
	 * <p><b>응답이 아예 오지 않은 경우도 일시적으로 본다.</b> 타임아웃·연결 끊김은 공급자가 상태를
	 * 알려 주지 못한 것이라 해제 여부를 알 수 없다. 다시 보내는 것이 안전한 근거는 멱등성이다 —
	 * 이미 폐기된 토큰에도 공급자는 성공을 준다.
	 */
	public static SocialUnlinkException from(String message, RestClientException cause) {
		return new SocialUnlinkException(message, cause, isRetryable(cause));
	}

	private static boolean isRetryable(RestClientException cause) {
		if (!(cause instanceof RestClientResponseException response)) {
			return true;
		}
		HttpStatusCode status = response.getStatusCode();
		return status.is5xxServerError() || status.value() == TOO_MANY_REQUESTS;
	}

	/** @return 같은 요청을 다시 보내는 것이 의미 있는 실패인지 */
	public boolean isRetryable() {
		return retryable;
	}
}
