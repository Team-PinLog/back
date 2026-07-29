package com.pinlog.pinlogback.global.security.oauth;

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

	/**
	 * 로그인 왕복에 필요한 시간. 사용자가 공급자 화면에 머무는 시간을 감안한다.
	 *
	 * <p>처음엔 180초였는데 실사용에 빠듯하다. 계정 선택 + 비밀번호 재입력 + 2단계 인증을 거치면
	 * 3분을 넘기는 경우가 있고, 넘기면 콜백에서 인가 요청을 찾지 못해 사용자는 이유도 모른 채
	 * {@code OAUTH_FAILED}로 돌아온다. 10분으로 늘렸다 — 이 쿠키가 들고 있는 것은 state와 PKCE
	 * verifier뿐이고 한 번 쓰면 즉시 지우므로, 늘려서 커지는 위험은 크지 않다.
	 */
	private static final int MAX_AGE_SECONDS = 600;

	/**
	 * 쿠키에서 읽은 바이트를 역직렬화하므로 허용 클래스를 제한한다.
	 * 제한하지 않으면 조작된 쿠키로 임의 클래스를 만들어내는 gadget 공격에 노출된다.
	 *
	 * <p><b>클래스 허용목록만으로는 부족하다.</b> {@code java.util.**}이 열려 있으면 중첩
	 * {@code HashSet}/{@code HashMap}으로 해시를 증폭시키는 자원 고갈(SerialDOS)이 그대로
	 * 통과한다 — {@code readObject}가 {@code hashCode()}를 재귀 호출해서, 수 KB짜리 쿠키 하나로
	 * CPU를 수 분간 태울 수 있다. 이 경로는 <b>인증 이전</b>에 돌고 입력은 전적으로 클라이언트가
	 * 준다. 그래서 클래스 제한 앞에 자원 한도를 함께 건다.
	 *
	 * <p>한도는 정상 payload(state 문자열 + PKCE verifier + 소수의 파라미터)보다 넉넉하되 증폭을
	 * 막을 수 있는 값으로 잡았다.
	 */
	private static final ObjectInputFilter DESERIALIZATION_FILTER = ObjectInputFilter.Config.createFilter(
		"maxdepth=20;maxrefs=1000;maxbytes=8192;maxarray=1000;"
			+ "org.springframework.security.oauth2.core.**;java.util.**;java.lang.**;!*");

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
