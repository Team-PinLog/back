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
	 * 알려 주지 못한 것이라 해제 여부를 알 수 없다.
	 *
	 * <p><b>다시 보내는 것이 안전하되, 성공으로 수렴한다는 보장은 없다.</b> RFC 7009 §2.2는 무효
	 * 토큰에도 200을 요구하지만 <b>3사가 그것을 따르지는 않는다.</b>
	 *
	 * <table>
	 *   <tr><th>공급자</th><th>이미 폐기된 토큰</th></tr>
	 *   <tr><td>Naver</td><td>{@code 200} — 문서가 "이미 폐기되었거나 존재하지 않는 경우"를 명시</td></tr>
	 *   <tr><td>Google</td><td>{@code 400} — 실제 호출로 확인했다</td></tr>
	 *   <tr><td>Kakao</td><td>미확인. 무효 토큰에 오류를 준다고 보는 편이 안전하다</td></tr>
	 * </table>
	 *
	 * <p>따라서 재시도가 흡수하는 것은 <b>요청이 닿지 못한 실패</b>(연결 거부·5xx)다. 요청이 닿아
	 * 해제까지 됐는데 <b>응답만 유실된</b> 경우는 다음 시도가 확정적 4xx를 받아 실패로 끝난다.
	 *
	 * <p>그 4xx를 "이미 해제된 것"으로 간주하지 않는다. 그렇게 하면 <b>실제로 해제되지 않았는데
	 * 회원을 지우는</b> 경로가 열리고, 그 방향은 마스킹 때문에 되돌릴 수 없다. 반대 방향(실패로
	 * 보고 사용자가 다시 시도)은 회복 가능하다 — BD-48이 순서를 정한 것과 같은 기준이다.
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
