package com.pinlog.pinlogback.global.security;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 안전한 요청에서도 {@code XSRF-TOKEN} 쿠키가 나가도록 토큰 해석을 강제한다.
 *
 * <p>{@code CsrfConfigurer.spa()}가 쓰는 {@code SpaCsrfTokenRequestHandler}는 토큰을 <b>지연</b>
 * 로딩한다. 누군가 값을 실제로 꺼내야 저장소가 쿠키를 내려보내는데, 조회 요청은 토큰을 꺼낼 일이
 * 없어 쿠키가 발급되지 않는다. 그러면 클라이언트는 첫 상태 변경 요청에 넣을 토큰을 얻을 방법이
 * 없고 영영 403을 받는다.
 *
 * <p>{@link CsrfToken#getToken()} 호출이 그 지연을 푸는 표준적인 방법이다.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		CsrfToken csrfToken = (CsrfToken)request.getAttribute(CsrfToken.class.getName());
		if (csrfToken != null) {
			csrfToken.getToken();
		}
		filterChain.doFilter(request, response);
	}
}
