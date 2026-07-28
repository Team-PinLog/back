package com.pinlog.pinlogback.global.security.token;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;


import jakarta.servlet.http.HttpServletResponse;

/**
 * 인증 쿠키 3종을 만든다(BD-21, 08 §1.1).
 *
 * <p>{@code Secure}를 로컬에서도 끄지 않는다. 브라우저는 {@code http://localhost}를 신뢰할 수 있는
 * 오리진으로 취급해 {@code Secure} 쿠키를 그대로 보내므로, 로컬 전용 예외를 두면 운영과 다른 경로를
 * 검증하게 될 뿐이다.
 *
 * <p>Refresh만 경로를 좁히는 이유: 일반 API 요청마다 7일짜리 토큰이 실려 나가면 노출 면적이 그만큼
 * 넓어진다. 재발급·로그아웃이 모두 {@code /v1/auth} 아래 있어 이 범위로 충분하다.
 */
@Component
public class AuthCookies {

	public static final String ACCESS_TOKEN = "access_token";
	public static final String REFRESH_TOKEN = "refresh_token";
	/** UI 힌트 전용. JS가 읽어야 하므로 HttpOnly가 아니고, <b>인가 판단에 쓰지 않는다</b>. */
	public static final String LOGGED_IN = "logged_in";

	private static final String SAME_SITE = "Lax";

	private final JwtProperties properties;
	private final String basePath;
	private final String refreshPath;

	public AuthCookies(
		JwtProperties properties,
		@Value("${server.servlet.context-path}") String contextPath
	) {
		this.properties = properties;
		this.basePath = contextPath;
		this.refreshPath = contextPath + "/v1/auth";
	}

	/** 발급한 토큰 쌍을 쿠키로 응답에 싣는다. 리다이렉트로 응답이 커밋되기 전에 호출해야 한다. */
	public void write(HttpServletResponse response, String accessToken, String refreshToken) {
		add(response, accessToken(accessToken, properties.accessTokenTtl()));
		add(response, refreshToken(refreshToken, properties.refreshTokenTtl()));
		add(response, loggedIn("1", properties.refreshTokenTtl()));
	}

	/** 세 쿠키를 즉시 만료시킨다. 값을 비우는 것만으로는 브라우저에 남는다. */
	public void clear(HttpServletResponse response) {
		add(response, accessToken("", Duration.ZERO));
		add(response, refreshToken("", Duration.ZERO));
		add(response, loggedIn("", Duration.ZERO));
	}

	private ResponseCookie accessToken(String value, Duration maxAge) {
		return base(ACCESS_TOKEN, value, maxAge).httpOnly(true).path(basePath).build();
	}

	private ResponseCookie refreshToken(String value, Duration maxAge) {
		return base(REFRESH_TOKEN, value, maxAge).httpOnly(true).path(refreshPath).build();
	}

	private ResponseCookie loggedIn(String value, Duration maxAge) {
		return base(LOGGED_IN, value, maxAge).httpOnly(false).path(basePath).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String name, String value, Duration maxAge) {
		return ResponseCookie.from(name, value).secure(true).sameSite(SAME_SITE).maxAge(maxAge);
	}

	private void add(HttpServletResponse response, ResponseCookie cookie) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}
}
