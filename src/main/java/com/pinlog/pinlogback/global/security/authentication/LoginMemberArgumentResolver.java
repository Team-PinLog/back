package com.pinlog.pinlogback.global.security.authentication;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.pinlog.pinlogback.global.exception.UnauthorizedException;

/**
 * {@link LoginMember} 파라미터를 SecurityContext에서 해석한다(authentication.md 2).
 *
 * <p>인가 규칙이 이미 미인증 요청을 막지만({@code anyRequest().authenticated()}) 여기서도 다시
 * 확인한다. 나중에 공개 경로가 늘었을 때 principal이 조용히 null로 들어가는 편보다 401로 끊기는
 * 편이 안전하다 — fail-closed가 기본값이다.
 */
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(LoginMember.class)
			&& MemberPrincipal.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public MemberPrincipal resolveArgument(
		MethodParameter parameter,
		@Nullable ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest,
		@Nullable WebDataBinderFactory binderFactory
	) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			throw new UnauthorizedException();
		}
		if (authentication.getPrincipal() instanceof MemberPrincipal principal) {
			return principal;
		}
		throw new UnauthorizedException();
	}
}
