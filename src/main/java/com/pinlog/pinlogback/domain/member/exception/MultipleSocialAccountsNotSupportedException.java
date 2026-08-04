package com.pinlog.pinlogback.domain.member.exception;

import com.pinlog.pinlogback.global.exception.BusinessException;
import com.pinlog.pinlogback.global.exception.ErrorCode;

/**
 * 소셜 계정이 둘 이상인 회원의 탈퇴를 막는다(BD-48 §⑥).
 *
 * <p>한 번의 인가 왕복은 한 공급자만 인가하는데 소프트 삭제는 그 회원의 계정을 <b>전부</b>
 * 마스킹한다. 하나만 끊고 지우면 나머지는 {@code provider_user_id}가 파기돼 영구히 못 끊는다 —
 * 이 설계가 막으려던 상태를 이 설계가 만든다.
 *
 * <p><b>500이 아니라 409다.</b> 지금은 도달할 수 없지만({@code SocialAccount.create}의 호출부가
 * 로그인 하나뿐이다) 도달했을 때 이것은 터진 것이 아니라 <b>막은 것</b>이다. 500으로 나가면 운영
 * 알림이 버그로 울어 "의도한 차단"과 "실제 장애"가 로그에서 구분되지 않는다.
 *
 * <p>메시지를 덧붙이지 않는다 — {@link BusinessException}의 두 인자 생성자는 그 문자열을 그대로
 * 응답에 싣는다. {@code memberId}·계정 수는 내부 값이므로 던지는 쪽이 로그로 남긴다.
 */
public class MultipleSocialAccountsNotSupportedException extends BusinessException {

	public MultipleSocialAccountsNotSupportedException() {
		super(ErrorCode.WITHDRAWAL_NOT_SUPPORTED);
	}
}
