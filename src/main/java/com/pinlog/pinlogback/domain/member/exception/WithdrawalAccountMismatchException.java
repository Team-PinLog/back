package com.pinlog.pinlogback.domain.member.exception;

/**
 * 탈퇴 왕복에서 공급자가 인증한 계정이 탈퇴를 요청한 회원의 것과 다르다(BD-48 §⑤).
 *
 * <p>사용자가 공급자 화면에서 <b>다른 계정으로 인증</b>할 수 있다. 그대로 두면 계정 B의 연결을 끊고
 * 회원 A를 삭제한다.
 *
 * <p>{@link com.pinlog.pinlogback.global.exception.BusinessException}이 아니다 — 이 예외가 나는
 * 지점은 필터 체인 안의 콜백 처리라 공통 envelope를 타지 않고, 클라이언트 복귀 경로에
 * {@code ?error=WITHDRAWAL_ACCOUNT_MISMATCH}로 실린다. ErrorCode를 붙이면 <b>렌더되지 않는 코드</b>가
 * 하나 늘 뿐이다.
 */
public class WithdrawalAccountMismatchException extends RuntimeException {

	public WithdrawalAccountMismatchException(Long memberId) {
		super("authenticated provider account does not belong to memberId=" + memberId);
	}
}
