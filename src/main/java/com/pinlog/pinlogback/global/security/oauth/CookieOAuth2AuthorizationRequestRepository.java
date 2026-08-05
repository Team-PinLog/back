package com.pinlog.pinlogback.global.security.oauth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인가 요청(state·PKCE verifier 포함)을 세션이 아니라 쿠키에 담는다.
 *
 * <p>기본 구현인 {@code HttpSessionOAuth2AuthorizationRequestRepository}는 {@code HttpSession}을
 * 만든다. 우리는 {@code SessionCreationPolicy.STATELESS}를 선언했고 인증 상태를 전적으로 쿠키에
 * 두기로 했으므로(11_인증_설계 2), 세션이 생기면 그 선언과 어긋난다.
 *
 * <p>쿠키는 로그인 왕복 동안만 필요해 수명을 짧게 두고, {@code Path}를 인증 경로로 제한해
 * 일반 API 요청에 실리지 않게 한다.
 *
 * <p><b>값은 JSON이다. Java 직렬화를 쓰지 않는다.</b> BD-30이 처음에 Java 직렬화를 고르고 역직렬화
 * 허용목록으로 막았는데, 그 결정을 뒤집었다. 근거 셋이다.
 *
 * <ul>
 *   <li>Spring Security가 <i>"classes are not intended to be serializable between different
 *       versions"</i>라고 명시한다. BD-30이 스스로 적어 둔 재검토 트리거(메이저 업그레이드)가
 *       7.x에서 발동했고, {@code SpringSecurityCoreVersion.SERIAL_VERSION_UID}는 제거 예정이다.</li>
 *   <li>조작 가능한 쿠키를 {@code readObject}에 넘기는 것은 CWE-502이고, 허용목록은 그 자체로
 *       충분한 완화가 아니다 — <i>"new gadgets are constantly being discovered"</i>.</li>
 *   <li>이 쿠키가 이제 탈퇴 대상 회원을 나른다(BD-48 §③). 위조의 결과가 "로그인 실패"에서
 *       "임의 계정 삭제"로 올라갔다.</li>
 * </ul>
 *
 * <p>BD-30이 JSON 안을 기각한 이유(<i>"재구성 코드를 직접 유지해야 하고 필드 누락이 조용한 버그가
 * 된다"</i>)는 더는 성립하지 않는다. Spring Security가 그 클래스의 직렬화기를 직접 제공한다.
 */
@Component
public class CookieOAuth2AuthorizationRequestRepository
	implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	static final String COOKIE_NAME = "oauth2_auth_request";

	/**
	 * 소비한 인가 요청을 콜백 요청 안에 남겨 두는 자리.
	 *
	 * <p>콜백이 로그인인지 탈퇴인지는 인가 요청 {@code attributes}만 안다(BD-48 §③). 그런데
	 * {@code OAuth2LoginAuthenticationFilter}가 그것을 <b>필터 안에서 소비</b>하고, 성공 핸들러에
	 * 넘기는 {@code OAuth2AuthenticationToken}에는 담지 않는다. 소비하는 지점이 여기이므로 여기서
	 * 남긴다 — 쿠키를 다시 읽어 역직렬화를 두 번 하는 것보다 낫다.
	 */
	private static final String CONSUMED_ATTRIBUTE =
		CookieOAuth2AuthorizationRequestRepository.class.getName() + ".consumed";

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
	 * 쿠키 값을 담는 형식. <b>Java 직렬화를 쓰지 않는다</b>(BD-30 정정).
	 *
	 * <p>직렬화기를 손으로 짜지 않고 Spring Security가 주는 것을 등록한다 —
	 * {@code OAuth2ClientJacksonModule}이 {@link OAuth2AuthorizationRequest}의 mixin과 deserializer를
	 * 갖고 있어 {@code attributes}와 {@code additionalParameters}까지 복원한다. 필드를 골라 담지
	 * 않으므로 BD-30이 JSON 안을 기각하며 든 "필드 누락이 조용한 버그가 된다"가 성립하지 않는다.
	 *
	 * <p>애플리케이션 공용 mapper를 쓰지 않는 이유는 이 모듈들이 <b>다형 타입 검증</b>을 켜기 때문이다.
	 * 그 설정이 일반 API 직렬화에까지 번지면 안 된다.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder()
		.addModules(SecurityJacksonModules
			.getModules(CookieOAuth2AuthorizationRequestRepository.class.getClassLoader())
			.toArray(new JacksonModule[0]))
		.build();

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
		if (authorizationRequest != null) {
			request.setAttribute(CONSUMED_ATTRIBUTE, authorizationRequest);
		}
		return authorizationRequest;
	}

	/**
	 * 이 요청에서 소비된 인가 요청. 콜백 성공·실패 처리가 왕복의 의도를 읽는 통로다.
	 *
	 * @return 아직 소비되지 않았거나 쿠키가 없었으면 빈 값
	 */
	public static Optional<OAuth2AuthorizationRequest> consumedAuthorizationRequest(
		HttpServletRequest request) {
		return Optional.ofNullable(
			(OAuth2AuthorizationRequest)request.getAttribute(CONSUMED_ATTRIBUTE));
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

	/** base64url로 감싸는 것은 쿠키 값에 못 쓰는 문자를 피하려는 것이다. 비밀이 아니다. */
	private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
		try {
			return Base64.getUrlEncoder().encodeToString(
				MAPPER.writeValueAsString(authorizationRequest).getBytes(StandardCharsets.UTF_8));
		} catch (JacksonException ex) {
			throw new IllegalStateException("인가 요청을 쿠키로 직렬화하지 못했습니다.", ex);
		}
	}

	/**
	 * 읽지 못하는 값은 <b>전부</b> "인가 요청 없음"으로 떨어뜨린다. Spring이
	 * {@code authorization_request_not_found}로 처리하고 실패 핸들러가 클라이언트로 돌려보낸다.
	 *
	 * <p>형식을 바꾼 배포 직후에는 <b>옛 Java 직렬화 쿠키</b>가 이 경로로 들어온다. 그 사용자는
	 * 로그인(또는 탈퇴)을 한 번 다시 하면 되고, 이미 있는 {@code OAUTH_FAILED} 복귀 경로를 탄다 —
	 * 쿠키 수명이 10분이라 사용자가 공급자 화면에 오래 머물렀을 때 원래 발생하던 것과 같다.
	 */
	private @Nullable OAuth2AuthorizationRequest deserialize(String value) {
		try {
			String json = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			return MAPPER.readValue(json, OAuth2AuthorizationRequest.class);
		} catch (JacksonException | IllegalArgumentException ex) {
			return null;
		}
	}
}
