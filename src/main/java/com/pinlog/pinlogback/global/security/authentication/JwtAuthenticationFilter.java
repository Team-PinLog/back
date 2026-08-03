package com.pinlog.pinlogback.global.security.authentication;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.global.exception.ErrorCode;
import com.pinlog.pinlogback.global.security.error.SecurityErrorWriter;
import com.pinlog.pinlogback.global.security.token.AuthCookies;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Access 쿠키를 읽어 SecurityContext를 채운다(authentication.md 3).
 *
 * <p>검증에 실패해도 여기서 401을 쓰지 않는다. 컨텍스트를 비워 둔 채 통과시키면 인가 규칙이
 * {@code RestAuthenticationEntryPoint}로 넘겨 공통 envelope 401을 만든다 — 오류 응답 형식이
 * 한 곳에만 있어야 계약이 어긋나지 않는다.
 *
 * <p><b>인프라 실패는 예외다.</b> 탈퇴 판정이 DB를 읽다 실패하면 자격증명 문제가 아니므로 401로
 * 말하지 않고 여기서 503을 직접 쓴다(BD-47). 삼켜서 통과시키면 클라이언트가 재로그인을 유도해
 * <b>일시적 순단이 전면 로그아웃으로 번진다.</b> 위 "형식이 한 곳에만" 원칙은 지킨다 —
 * {@code RestAuthenticationEntryPoint}와 같은 {@link SecurityErrorWriter}를 쓴다.
 *
 * <p><b>서명·만료만으로는 부족해 탈퇴 여부를 함께 본다.</b> Access는 30분짜리이고 폐기 목록이
 * 없으므로, 토큰만 검사하면 탈퇴한 회원이 <b>다른 기기에 남은 쿠키로 최대 30분간 읽기·쓰기를</b>
 * 계속할 수 있다. Refresh 폐기는 재발급만 막고 이미 발급된 Access는 막지 못한다. 대가는 인증
 * 요청마다 PK 조회 한 번이며, 판정은 {@link MemberRepository#isActive}에 모여 있다 —
 * S15P11A705-147이 그 목적으로 만든 지점이다.
 *
 * <p>이미 인증된 요청은 건드리지 않는다. OAuth 로그인 흐름이 자기 인증을 이미 올려 둔 경우가 있다.
 *
 * <p><b>빈으로 만들지 않는다.</b> Spring Boot는 {@code Filter} 타입 빈을 서블릿 컨테이너에도 자동
 * 등록하므로, {@code @Component}를 붙이면 Security 체인 안팎에 두 번 등록된다. 그러면 이 필터가
 * {@code SecurityContextHolderFilter}보다 먼저 도는 경로가 생겨, 뒤이어 빈 컨텍스트로 덮이는
 * 순서 의존 버그가 열린다. {@code SecurityConfig}가 직접 생성해 체인에만 물린다.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtTokenProvider tokenProvider;
	private final MemberRepository memberRepository;
	private final SecurityErrorWriter errorWriter;

	public JwtAuthenticationFilter(
		JwtTokenProvider tokenProvider,
		MemberRepository memberRepository,
		SecurityErrorWriter errorWriter
	) {
		this.tokenProvider = tokenProvider;
		this.memberRepository = memberRepository;
		this.errorWriter = errorWriter;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				readAccessToken(request)
					.flatMap(tokenProvider::parseAccessToken)
					.filter(memberRepository::isActive)
					.ifPresent(memberId -> authenticate(request, memberId));
			} catch (DataAccessException e) {
				// 자격증명은 멀쩡하고 우리가 확인을 못 한 것이다. 삼켜서 401을 내면 클라이언트가
				// 재발급을 시도하는데, 재발급은 Redis만 쓰므로 성공한다 — 401과 재발급이 DB가
				// 회복될 때까지 번갈아 돌고, 동시 요청이 겹치면 재사용 감지로 전 세션이 폐기된다
				// (S15P11A705-267). 일시적 순단이 영구 로그아웃이 되는 경로다(BD-47).
				log.error("member activity check failed, responding {}", ErrorCode.AUTH_UNAVAILABLE, e);
				errorWriter.write(response, ErrorCode.AUTH_UNAVAILABLE);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	private static Optional<String> readAccessToken(HttpServletRequest request) {
		return Optional.ofNullable(WebUtils.getCookie(request, AuthCookies.ACCESS_TOKEN))
			.map(Cookie::getValue)
			.filter(value -> !value.isBlank());
	}

	private void authenticate(HttpServletRequest request, Long memberId) {
		UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
			new MemberPrincipal(memberId), null, List.of());
		authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}
}
