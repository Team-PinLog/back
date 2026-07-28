package com.pinlog.pinlogback.global.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Access 쿠키를 읽어 SecurityContext를 채운다(authentication.md 3).
 *
 * <p>검증에 실패해도 여기서 401을 쓰지 않는다. 컨텍스트를 비워 둔 채 통과시키면 인가 규칙이
 * {@code RestAuthenticationEntryPoint}로 넘겨 공통 envelope 401을 만든다 — 오류 응답 형식이
 * 한 곳에만 있어야 계약이 어긋나지 않는다.
 *
 * <p>이미 인증된 요청은 건드리지 않는다. OAuth 로그인 흐름이 자기 인증을 이미 올려 둔 경우가 있다.
 *
 * <p><b>빈으로 만들지 않는다.</b> Spring Boot는 {@code Filter} 타입 빈을 서블릿 컨테이너에도 자동
 * 등록하므로, {@code @Component}를 붙이면 Security 체인 안팎에 두 번 등록된다. 그러면 이 필터가
 * {@code SecurityContextHolderFilter}보다 먼저 도는 경로가 생겨, 뒤이어 빈 컨텍스트로 덮이는
 * 순서 의존 버그가 열린다. {@code SecurityConfig}가 직접 생성해 체인에만 물린다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtTokenProvider tokenProvider;

	public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
		this.tokenProvider = tokenProvider;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			readAccessToken(request)
				.flatMap(tokenProvider::parseAccessToken)
				.ifPresent(memberId -> authenticate(request, memberId));
		}
		filterChain.doFilter(request, response);
	}

	private void authenticate(HttpServletRequest request, Long memberId) {
		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
			new MemberPrincipal(memberId), null, List.of());
		authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	private Optional<String> readAccessToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (AuthCookies.ACCESS_TOKEN.equals(cookie.getName())) {
				return Optional.ofNullable(cookie.getValue()).filter(value -> !value.isBlank());
			}
		}
		return Optional.empty();
	}
}
