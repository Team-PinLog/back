package com.pinlog.pinlogback.global.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인가 요청(state·PKCE verifier 포함)을 세션이 아니라 쿠키에 담는다.
 *
 * <p>기본 구현인 {@code HttpSessionOAuth2AuthorizationRequestRepository}는 {@code HttpSession}을
 * 만든다. 우리는 {@code SessionCreationPolicy.STATELESS}를 선언했고 인증 상태를 전적으로 쿠키에
 * 두기로 했으므로(11_인증_설계 2), 세션이 생기면 그 선언과 어긋난다.
 *
 * <p>쿠키는 로그인 왕복 동안만 필요해 수명을 짧게 두고, {@code Path}를 인증 경로로 제한해
 * 일반 API 요청에 실리지 않게 한다.
 */
@Component
public class CookieOAuth2AuthorizationRequestRepository
	implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	static final String COOKIE_NAME = "oauth2_auth_request";

	/** 로그인 왕복에 필요한 시간만. 사용자가 공급자 화면에서 머무는 시간을 감안한 값이다. */
	private static final int MAX_AGE_SECONDS = 180;

	/**
	 * 쿠키에서 읽은 바이트를 역직렬화하므로 허용 클래스를 제한한다.
	 * 제한하지 않으면 조작된 쿠키로 임의 클래스를 만들어내는 gadget 공격에 노출된다.
	 */
	private static final ObjectInputFilter DESERIALIZATION_FILTER = ObjectInputFilter.Config.createFilter(
		"org.springframework.security.oauth2.core.**;java.util.**;java.lang.**;!*");

	@Override
	public @Nullable OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		return findCookie(request).map(Cookie::getValue).map(this::deserialize).orElse(null);
	}

	/**
	 * @param authorizationRequest 인터페이스는 non-null로 선언하지만, Spring 기본 구현과 마찬가지로
	 *     null을 "저장할 것이 없으니 지운다"로 받아들인다. 오버라이드에서 파라미터의 nullability를
	 *     넓히는 것은 허용된다.
	 */
	@Override
	public void saveAuthorizationRequest(
		@Nullable OAuth2AuthorizationRequest authorizationRequest,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		if (authorizationRequest == null) {
			expire(request, response);
			return;
		}
		response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, serialize(authorizationRequest))
			.maxAge(MAX_AGE_SECONDS)
			.build()
			.toString());
	}

	@Override
	public @Nullable OAuth2AuthorizationRequest removeAuthorizationRequest(
		HttpServletRequest request,
		HttpServletResponse response
	) {
		OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
		expire(request, response);
		return authorizationRequest;
	}

	private void expire(HttpServletRequest request, HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "").maxAge(0).build().toString());
	}

	private ResponseCookie.ResponseCookieBuilder cookie(HttpServletRequest request, String value) {
		return ResponseCookie.from(COOKIE_NAME, value)
			.httpOnly(true)
			// 로컬 개발은 http라 Secure를 강제하면 쿠키가 돌아오지 않는다. 운영은 항상 https다.
			.secure(request.isSecure())
			.sameSite("Lax")
			.path(request.getContextPath() + "/v1/auth");
	}

	private Optional<Cookie> findCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		return Arrays.stream(cookies)
			.filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
			.filter(cookie -> cookie.getValue() != null && !cookie.getValue().isBlank())
			.findFirst();
	}

	private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
			out.writeObject(authorizationRequest);
		} catch (IOException ex) {
			throw new IllegalStateException("인가 요청을 쿠키로 직렬화하지 못했습니다.", ex);
		}
		return Base64.getUrlEncoder().encodeToString(buffer.toByteArray());
	}

	private @Nullable OAuth2AuthorizationRequest deserialize(String value) {
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(value);
			try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(decoded))) {
				in.setObjectInputFilter(DESERIALIZATION_FILTER);
				return (OAuth2AuthorizationRequest)in.readObject();
			}
		} catch (IOException | ClassNotFoundException | IllegalArgumentException ex) {
			// 조작·만료된 쿠키는 "인가 요청 없음"으로 취급한다. Spring이 authorization_request_not_found로
			// 처리하고 실패 핸들러가 클라이언트로 돌려보낸다.
			return null;
		}
	}
}
