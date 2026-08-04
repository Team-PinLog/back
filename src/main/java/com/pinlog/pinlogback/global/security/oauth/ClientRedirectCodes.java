package com.pinlog.pinlogback.global.security.oauth;

/**
 * 클라이언트 복귀 경로에 싣는 결과 코드(BD-48 §④).
 *
 * <p><b>규격 밖의 어휘다.</b> 공급자가 우리 콜백에 주는 코드는 RFC 6749 §4.1.2.1이 규정하고
 * ({@code access_denied}·{@code server_error} …) 우리가 만드는 값이 아니다. 여기 있는 것은 그것을
 * 받아 프론트에게 다시 말하는 층이며, 기존 {@link #OAUTH_FAILED} 선례를 따른다.
 *
 * <p>성공 핸들러와 실패 핸들러가 같은 어휘를 써야 해서 한자리에 모은다 — 나뉘어 있으면 한쪽만
 * 고쳐도 컴파일이 통과한다.
 */
public final class ClientRedirectCodes {

	/** 로그인 왕복 실패. 사유를 그대로 노출하지 않고 고정 코드 하나만 준다. */
	public static final String OAUTH_FAILED = "OAUTH_FAILED";

	/** 사용자가 공급자 화면에서 취소했다({@code access_denied}). */
	public static final String WITHDRAWAL_CANCELLED = "WITHDRAWAL_CANCELLED";

	/** 해제 호출이 실패했거나, 해제는 됐는데 삭제가 실패했다. 진입에서 티켓이 깨진 경우도 같다. */
	public static final String WITHDRAWAL_FAILED = "WITHDRAWAL_FAILED";

	/** 해제 대상을 판정하지 못했다 — 담당 클라이언트가 없는 공급자. */
	public static final String WITHDRAWAL_UNLINK_FAILED = "WITHDRAWAL_UNLINK_FAILED";

	/** 인증된 공급자 계정이 탈퇴를 요청한 회원의 것이 아니다(BD-48 §⑤). */
	public static final String WITHDRAWAL_ACCOUNT_MISMATCH = "WITHDRAWAL_ACCOUNT_MISMATCH";

	/** 탈퇴가 확정됐다. 오류가 아니므로 {@code ?error=}가 아닌 자기 파라미터로 나간다. */
	public static final String WITHDRAWAL_COMPLETED_PARAMETER = "withdrawal";
	public static final String WITHDRAWAL_COMPLETED = "completed";

	private ClientRedirectCodes() {
	}
}
