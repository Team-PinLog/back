package com.pinlog.pinlogback.domain.auth.controller;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.auth.service.AuthTokenService;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService.TokenPair;
import com.pinlog.pinlogback.global.exception.UnauthorizedException;
import com.pinlog.pinlogback.global.security.token.AuthCookies;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 재발급과 로그아웃(API 명세 3.3·3.4).
 *
 * <p>둘 다 본문이 없는 {@code 204}다. 응답에 토큰을 담지 않는 것이 쿠키를 택한 이유 자체이고
 * (BD-21), 본문이 없으므로 공통 envelope도 적용되지 않는다({@code ApiResponseBodyAdvice}가
 * {@code null} body를 그대로 통과시킨다).
 *
 * <p>상태를 바꾸는 요청이므로 {@code X-XSRF-TOKEN}이 필요하다. 누락·불일치는 여기 도달하기 전에
 * Security가 403으로 끊는다.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthTokenController {

	private final AuthTokenService authTokenService;
	private final AuthCookies authCookies;

	public AuthTokenController(AuthTokenService authTokenService, AuthCookies authCookies) {
		this.authTokenService = authTokenService;
		this.authCookies = authCookies;
	}

	/**
	 * 실패하면 쿠키를 지운다. 401만 돌려주고 쿠키를 남기면 클라이언트는 죽은 Refresh를 계속 보내고,
	 * 그때마다 재사용으로 판정돼 폐기가 다시 돈다 — 그 사이 새로 로그인한 세션까지 끊긴다.
	 * {@code logged_in}도 남아 UI가 로그인 상태를 계속 가리키므로, 빠져나갈 상태 전이가 없어진다.
	 *
	 * <p>{@code GlobalExceptionHandler}가 아니라 여기서 지우는 이유: 401은 만료된 Access로 보호
	 * 자원을 찍었을 때도 나온다. 전역에서 지우면 <b>재발급하면 될 상황에 세션을 끊는다.</b> 세션이
	 * 끝났다고 단정할 수 있는 곳은 재발급이 실패한 이 지점뿐이다.
	 */
	@PostMapping("/refresh")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void refresh(
		@CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) @Nullable String refreshToken,
		HttpServletResponse response
	) {
		TokenPair tokens;
		try {
			tokens = authTokenService.rotate(refreshToken);
		} catch (UnauthorizedException e) {
			authCookies.clear(response);
			throw e;
		}
		authCookies.write(response, tokens.accessToken(), tokens.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(
		@CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) @Nullable String refreshToken,
		HttpServletResponse response
	) {
		authTokenService.logout(refreshToken);
		// 서버 상태와 무관하게 쿠키는 항상 지운다. 로그아웃은 멱등하다.
		authCookies.clear(response);
	}
}
