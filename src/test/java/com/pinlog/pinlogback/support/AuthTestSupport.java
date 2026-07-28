package com.pinlog.pinlogback.support;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 테스트 인증 주입을 한 곳으로 모은다(back#28 계약).
 *
 * <p>지금은 인증 스텁의 X-Debug-Member-Id 헤더를 넣는다. 인증 PR이 병합되면 이 메서드 본문만
 * spring-security-test 지원(쿠키·csrf())으로 바뀌고, 이 헬퍼를 쓰는 테스트는 그대로 남는다.
 */
public final class AuthTestSupport {

	private AuthTestSupport() {
	}

	public static RequestPostProcessor loginAs(long memberId) {
		return request -> {
			request.addHeader("X-Debug-Member-Id", String.valueOf(memberId));
			return request;
		};
	}
}
