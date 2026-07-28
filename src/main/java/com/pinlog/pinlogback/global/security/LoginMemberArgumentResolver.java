package com.pinlog.pinlogback.global.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.pinlog.pinlogback.global.exception.UnauthorizedException;

/**
 * 인증 스텁(back#28 계약). Spring Security를 도입하지 않고 순수 MVC 리졸버로
 * {@link MemberPrincipal}을 만든다 — 인증 PR의 SecurityConfig 영역을 점유하지 않기 위해서다.
 *
 * <p>{@code pinlog.auth.stub.enabled=true}(local·test만)일 때만 {@code X-Debug-Member-Id} 헤더를
 * 채택하고, 그 외 프로파일에서는 항상 401이다(fail-closed — 우회가 아니라 차단이 기본값).
 *
 * <p><b>인증 PR이 오면 헤더 분기와 프로퍼티를 반드시 제거한다.</b> 남기면 운영 인증 우회 구멍이 된다.
 */
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

	static final String DEBUG_MEMBER_ID_HEADER = "X-Debug-Member-Id";

	private final boolean stubEnabled;

	public LoginMemberArgumentResolver(boolean stubEnabled) {
		this.stubEnabled = stubEnabled;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(LoginMember.class)
			&& MemberPrincipal.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		if (!stubEnabled) {
			throw new UnauthorizedException();
		}
		String headerValue = webRequest.getHeader(DEBUG_MEMBER_ID_HEADER);
		if (headerValue == null || headerValue.isBlank()) {
			throw new UnauthorizedException();
		}
		try {
			return new MemberPrincipal(Long.parseLong(headerValue));
		} catch (NumberFormatException e) {
			throw new UnauthorizedException();
		}
	}
}
