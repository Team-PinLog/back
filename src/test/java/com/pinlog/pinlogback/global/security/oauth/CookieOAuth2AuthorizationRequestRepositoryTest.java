package com.pinlog.pinlogback.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

import jakarta.servlet.http.Cookie;

@DisplayName("인가 요청 쿠키 보관")
class CookieOAuth2AuthorizationRequestRepositoryTest {

	private static final String COOKIE_NAME = "oauth2_auth_request";

	private final CookieOAuth2AuthorizationRequestRepository repository =
		new CookieOAuth2AuthorizationRequestRepository();

	@Test
	@DisplayName("쿠키 값은 JSON이다 — Java 직렬화 스트림이 아니다")
	void theCookieCarriesJsonNotAJavaSerializationStream() {
		// Java 직렬화 형식은 Spring Security 버전 간 호환이 보장되지 않는다("classes are not
		// intended to be serializable between different versions"). 그리고 조작 가능한 쿠키를
		// readObject에 넘기는 것은 CWE-502이며, 허용목록은 그 자체로 충분한 완화가 아니다
		// ("new gadgets are constantly being discovered"). BD-30의 재검토 트리거가 발동했다.
		MockHttpServletResponse response = new MockHttpServletResponse();
		repository.saveAuthorizationRequest(
			authorizationRequest(), new MockHttpServletRequest(), response);

		byte[] decoded = Base64.getUrlDecoder().decode(cookieValueFrom(response));

		assertThat(decoded).as("Java 직렬화 매직 넘버(0xACED)가 없어야 한다")
			.startsWith((byte)'{');
		assertThat(new String(decoded, StandardCharsets.UTF_8))
			.contains("\"state\"")
			.contains("\"attributes\"");
	}

	@Test
	@DisplayName("실제 인가 요청의 쿠키가 브라우저 상한에 여유를 두고 들어간다")
	void theCookieStaysWellUnderTheBrowserLimit() {
		// JSON에는 @class 타입 정보가 붙어 Java 직렬화보다 작다는 보장이 없다. 브라우저의 쿠키당
		// 상한은 4096바이트이고, 넘으면 조용히 잘리거나 버려져 로그인이 통째로 깨진다.
		// scope가 늘거나 공급자가 추가되면 커지는 값이라 회귀로 고정한다.
		MockHttpServletResponse response = new MockHttpServletResponse();
		repository.saveAuthorizationRequest(realisticAuthorizationRequest(), new MockHttpServletRequest(),
			response);

		int cookieBytes = response.getHeader(HttpHeaders.SET_COOKIE).length();

		// 실측 2018바이트. 상한의 절반이라 여유가 있지만, JSON에 @class가 붙는 만큼 값이 늘면
		// 빠르게 자란다. 4KB에 근접하면 BD-30의 재검토 트리거대로 Redis + 키 쿠키로 옮긴다.
		assertThat(cookieBytes).isLessThan(4096);
	}

	/**
	 * {@code DefaultOAuth2AuthorizationRequestResolver} + PKCE가 만드는 <b>형태와 길이</b>에 맞춘다.
	 *
	 * <p><b>값은 전부 지어낸 것이다.</b> 실제 실행 출력에서 복사해 오면 안 된다 — 공급자
	 * {@code client_id}처럼 계정을 특정하는 값이 저장소에 들어간다. 크기 단언이 목적이므로
	 * 필요한 것은 내용이 아니라 길이다.
	 */
	private OAuth2AuthorizationRequest realisticAuthorizationRequest() {
		return OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
			.clientId("0000000000000-testclientidsampletestclientidsa.apps.googleusercontent.com")
			.redirectUri("https://pinlog.example/api/core/v1/auth/google/callback")
			.scopes(Set.of("openid", "email"))
			.state("teststatevalueteststatevalueteststatevaluet=")
			.attributes(attributes -> {
				attributes.put("registration_id", "google");
				attributes.put(PkceParameterNames.CODE_VERIFIER,
					"testcodeverifiervaluetestcodeverifiervaluetestcodeverifiervaluetestcodeverifiervaluetest");
				attributes.put(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, "77");
			})
			.additionalParameters(parameters -> {
				parameters.put("nonce", "testnoncevaluetestnoncevaluetestnoncevaluet");
				parameters.put(PkceParameterNames.CODE_CHALLENGE,
					"testcodechallengevaluetestcodechallengevalu");
				parameters.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
			})
			.build();
	}

	@Test
	@DisplayName("PKCE code_verifier가 왕복에서 살아남는다")
	void pkceVerifierSurvivesTheRoundTrip() {
		// 잃으면 인가는 성공하고 토큰 교환만 invalid_grant으로 죽는다 — 증상이 콜백 실패
		// 하나로만 보여 원인을 찾기 어렵다. JSON 전환에서 가장 조용히 깨질 수 있는 값이다.
		MockHttpServletResponse response = new MockHttpServletResponse();
		repository.saveAuthorizationRequest(
			OAuth2AuthorizationRequest.from(authorizationRequest())
				.attributes(attributes -> attributes.put(PkceParameterNames.CODE_VERIFIER, "verifier-value"))
				.additionalParameters(parameters -> {
					parameters.put(PkceParameterNames.CODE_CHALLENGE, "challenge-value");
					parameters.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
				})
				.build(),
			new MockHttpServletRequest(), response);

		OAuth2AuthorizationRequest loaded =
			repository.loadAuthorizationRequest(requestWithCookieFrom(response));

		assertThat(loaded).isNotNull();
		assertThat(loaded.getAttributes())
			.containsEntry(PkceParameterNames.CODE_VERIFIER, "verifier-value");
		assertThat(loaded.getAdditionalParameters())
			.containsEntry(PkceParameterNames.CODE_CHALLENGE, "challenge-value")
			.containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
	}

	@Test
	@DisplayName("조작된 쿠키는 인가 요청 없음으로 떨어진다 — 예외가 새지 않는다")
	void tamperedCookieFailsClosed() {
		// BD-30이 "아직 회귀로 고정되지 않은 것"으로 남긴 항목이다. 여기서 예외가 새면
		// 필터 체인 밖으로 나가 컨테이너 기본 500이 되고, 명세가 정한 복귀 경로를 벗어난다.
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(COOKIE_NAME, "not-base64-and-not-json"));

		assertThat(repository.loadAuthorizationRequest(request)).isNull();
	}

	@Test
	@DisplayName("남의 형식이 담긴 쿠키도 인가 요청 없음이다")
	void cookieFromAnotherFormatFailsClosed() {
		// 배포 순간 진행 중이던 왕복의 옛 Java 직렬화 쿠키가 이 경로로 들어온다.
		// 사용자는 로그인을 한 번 다시 하면 되고, 그 실패는 이미 있는 OAUTH_FAILED 경로다.
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(COOKIE_NAME,
			Base64.getUrlEncoder().encodeToString(new byte[] {(byte)0xAC, (byte)0xED, 0x00, 0x05})));

		assertThat(repository.loadAuthorizationRequest(request)).isNull();
	}

	@Test
	@DisplayName("쿠키 속성을 고정한다 — HttpOnly · SameSite=Lax · Path")
	void cookieAttributesAreFixed() {
		// BD-30이 감수 항목의 근거로 삼은 성질인데 테스트가 없었다.
		MockHttpServletResponse response = new MockHttpServletResponse();
		repository.saveAuthorizationRequest(
			authorizationRequest(), new MockHttpServletRequest(), response);

		String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);

		assertThat(setCookie)
			.contains("HttpOnly")
			.contains("SameSite=Lax")
			.contains("Path=/v1/auth");
	}

	@Test
	@DisplayName("저장한 인가 요청을 쿠키에서 되읽는다")
	void roundTrip() {
		MockHttpServletResponse response = new MockHttpServletResponse();
		repository.saveAuthorizationRequest(
			authorizationRequest(), new MockHttpServletRequest(), response);

		OAuth2AuthorizationRequest loaded =
			repository.loadAuthorizationRequest(requestWithCookieFrom(response));

		assertThat(loaded).isNotNull();
		assertThat(loaded.getState()).isEqualTo("state-value");
		assertThat(loaded.getAttributes())
			.containsEntry(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, "77");
	}

	@Test
	@DisplayName("소비한 인가 요청을 콜백 처리가 볼 수 있게 남긴다")
	void consumedRequestStaysReadableWithinTheCallbackRequest() {
		// 콜백이 로그인인지 탈퇴인지는 인가 요청 attributes만 안다(BD-48 §③). 그런데 Spring은
		// 필터 안에서 이것을 소비해 버리고, 성공 핸들러에 넘기는 Authentication에는 담지 않는다.
		// 소비 지점이 여기이므로 여기서 같은 요청에 남긴다.
		MockHttpServletResponse saved = new MockHttpServletResponse();
		repository.saveAuthorizationRequest(
			authorizationRequest(), new MockHttpServletRequest(), saved);
		MockHttpServletRequest callback = requestWithCookieFrom(saved);

		repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());

		Optional<OAuth2AuthorizationRequest> consumed =
			CookieOAuth2AuthorizationRequestRepository.consumedAuthorizationRequest(callback);
		assertThat(consumed).isPresent();
		assertThat(consumed.get().getAttributes())
			.containsEntry(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, "77");
	}

	@Test
	@DisplayName("소비한 적이 없으면 빈 값이다")
	void nothingConsumedIsEmpty() {
		assertThat(CookieOAuth2AuthorizationRequestRepository.consumedAuthorizationRequest(
			new MockHttpServletRequest())).isEmpty();
	}

	private OAuth2AuthorizationRequest authorizationRequest() {
		return OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("https://kauth.kakao.com/oauth/authorize")
			.clientId("client")
			.redirectUri("https://pinlog.example/api/core/v1/auth/kakao/callback")
			.state("state-value")
			.attributes(attributes -> attributes.put(
				WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, "77"))
			.build();
	}

	private String cookieValueFrom(MockHttpServletResponse response) {
		String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
		return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
	}

	private MockHttpServletRequest requestWithCookieFrom(MockHttpServletResponse response) {
		String setCookie = response.getHeader("Set-Cookie");
		assertThat(setCookie).isNotNull();
		String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie("oauth2_auth_request", value));
		return request;
	}
}
