package com.pinlog.pinlogback.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;

import com.pinlog.pinlogback.global.exception.UnauthorizedException;

/**
 * 인증 스텁 계약(back#28) — pinlog.auth.stub.enabled=true(local·test만)일 때
 * X-Debug-Member-Id 헤더로 memberId를 얻고, 그 외에는 전부 401이다(fail-closed, 우회 아님).
 * 인증 PR이 오면 이 리졸버의 본문만 실제 인증으로 바뀌고 도메인 코드는 그대로 남는다.
 */
class LoginMemberArgumentResolverTest {

	@SuppressWarnings("unused")
	void sampleHandler(@LoginMember MemberPrincipal principal, String notPrincipal) {
	}

	@Test
	void supportsOnlyAnnotatedMemberPrincipalParameter() throws Exception {
		LoginMemberArgumentResolver resolver = new LoginMemberArgumentResolver(true);

		assertThat(resolver.supportsParameter(parameterAt(0))).isTrue();
		assertThat(resolver.supportsParameter(parameterAt(1))).isFalse();
	}

	@Test
	void resolvesPrincipalFromDebugHeaderWhenStubEnabled() throws Exception {
		LoginMemberArgumentResolver resolver = new LoginMemberArgumentResolver(true);
		NativeWebRequest request = mock(NativeWebRequest.class);
		given(request.getHeader("X-Debug-Member-Id")).willReturn("42");

		Object resolved = resolver.resolveArgument(parameterAt(0), null, request, null);

		assertThat(resolved).isEqualTo(new MemberPrincipal(42L));
	}

	@Test
	void missingHeaderIsUnauthorized() throws Exception {
		LoginMemberArgumentResolver resolver = new LoginMemberArgumentResolver(true);
		NativeWebRequest request = mock(NativeWebRequest.class);
		given(request.getHeader("X-Debug-Member-Id")).willReturn(null);

		assertThatThrownBy(() -> resolver.resolveArgument(parameterAt(0), null, request, null))
			.isInstanceOf(UnauthorizedException.class);
	}

	@Test
	void nonNumericHeaderIsUnauthorized() throws Exception {
		LoginMemberArgumentResolver resolver = new LoginMemberArgumentResolver(true);
		NativeWebRequest request = mock(NativeWebRequest.class);
		given(request.getHeader("X-Debug-Member-Id")).willReturn("not-a-number");

		assertThatThrownBy(() -> resolver.resolveArgument(parameterAt(0), null, request, null))
			.isInstanceOf(UnauthorizedException.class);
	}

	@Test
	void disabledStubIsAlwaysUnauthorizedEvenWithHeader() throws Exception {
		LoginMemberArgumentResolver resolver = new LoginMemberArgumentResolver(false);
		NativeWebRequest request = mock(NativeWebRequest.class);

		assertThatThrownBy(() -> resolver.resolveArgument(parameterAt(0), null, request, null))
			.isInstanceOf(UnauthorizedException.class);
	}

	private MethodParameter parameterAt(int index) throws Exception {
		return new MethodParameter(
			getClass().getDeclaredMethod("sampleHandler", MemberPrincipal.class, String.class), index);
	}
}
