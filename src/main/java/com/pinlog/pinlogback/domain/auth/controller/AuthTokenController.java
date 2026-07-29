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

	@PostMapping("/refresh")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void refresh(
		@CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) @Nullable String refreshToken,
		HttpServletResponse response
	) {
		TokenPair tokens = authTokenService.rotate(refreshToken);
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
