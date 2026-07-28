package com.pinlog.pinlogback.support;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

/**
 * 테스트 인증 주입을 한 곳으로 모은다(back#28 계약).
 *
 * <p>인증 스텁 시절에는 {@code X-Debug-Member-Id} 헤더를 넣었다. 인증 PR(S15P11A705-63)이
 * 병합되면서 <b>이 메서드 본문만</b> {@code spring-security-test} 지원으로 바뀌었고, 이를 쓰는
 * 도메인 테스트는 한 줄도 고치지 않았다 — 헬퍼를 한 곳에 모아 둔 판단이 값을 한 지점이다.
 *
 * <p>두 가지를 함께 넣는다.
 *
 * <ul>
 *   <li><b>인증</b> — {@link MemberPrincipal}을 principal로 담은 {@code Authentication}.
 *       실제 JWT를 만들지 않는 이유는 서명 키가 컨텍스트마다 다르고, 여기서 검증하려는 것이
 *       토큰이 아니라 <b>도메인 동작</b>이기 때문이다. 토큰 발급·검증 계약은
 *       {@code AuthTokenContractTests}가 실제 쿠키로 따로 본다.</li>
 *   <li><b>CSRF 토큰</b> — 인증 도입으로 상태 변경 요청에 {@code X-XSRF-TOKEN}이 필수가 됐다.
 *       넣지 않으면 {@code post}·{@code patch}·{@code delete} 테스트가 전부 403이 된다.
 *       조회 요청에 붙어도 무해하므로 갈라 두지 않는다.</li>
 * </ul>
 */
public final class AuthTestSupport {

	private AuthTestSupport() {
	}

	public static RequestPostProcessor loginAs(long memberId) {
		RequestPostProcessor authentication = SecurityMockMvcRequestPostProcessors.authentication(
			new UsernamePasswordAuthenticationToken(new MemberPrincipal(memberId), null, List.of()));
		RequestPostProcessor csrf = SecurityMockMvcRequestPostProcessors.csrf();
		return request -> csrf.postProcessRequest(authentication.postProcessRequest(request));
	}
}
