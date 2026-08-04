package com.pinlog.pinlogback.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import jakarta.servlet.http.Cookie;

/**
 * 공급자 access token이 성공 핸들러에 닿는지 확인한다.
 *
 * <p><b>이 순서가 어긋나면 탈퇴가 통째로 불가능하다.</b> 해제에 쓸 토큰은
 * {@code OAuth2AuthenticationToken}에 실리지 않아 저장소에서 꺼내는데, 저장하는 것도 꺼내 쓰는 것도
 * <b>Spring 필터 하나가 같은 요청 안에서</b> 하는 일이라 순서가 그 클래스 내부에 있다. 핸들러를
 * 단위로 부르면서 토큰을 손으로 넣어 두면 그 전제를 검증하지 못한다 — 실제로 저장되지 않아도
 * 테스트는 통과한다.
 *
 * <p>그래서 필터를 직접 태운다. 인증 자체는 관심사가 아니므로 {@code AuthenticationManager}를
 * 결과만 돌려주게 두고, 보는 것은 <b>핸들러가 불릴 시점에 저장소가 채워져 있는가</b> 하나다.
 */
@DisplayName("공급자 토큰 전달")
class ProviderTokenReachesSuccessHandlerTest {

	private static final String REGISTRATION_ID = "google";
	private static final String PROVIDER_ACCESS_TOKEN = "provider-access-token";
	private static final String STATE = "state-value";
	private static final String CALLBACK_URI =
		"https://pinlog.example/api/core/v1/auth/google/callback";

	private final ClientRegistration registration = ClientRegistration
		.withRegistrationId(REGISTRATION_ID)
		.clientId("client")
		.clientSecret("secret")
		.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
		.redirectUri(CALLBACK_URI)
		.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
		.tokenUri("https://oauth2.googleapis.com/token")
		.userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
		.userNameAttributeName("sub")
		.scope("openid", "email")
		.build();

	private final CookieOAuth2AuthorizationRequestRepository authorizationRequests =
		new CookieOAuth2AuthorizationRequestRepository();
	private final RequestScopedOAuth2AuthorizedClientRepository authorizedClients =
		new RequestScopedOAuth2AuthorizedClientRepository();

	@Test
	@DisplayName("성공 핸들러가 불릴 때 공급자 토큰이 이미 저장돼 있다")
	void theProviderTokenIsAvailableWhenTheSuccessHandlerRuns() throws Exception {
		AtomicReference<String> seenByHandler = new AtomicReference<>();

		OAuth2LoginAuthenticationFilter filter = new OAuth2LoginAuthenticationFilter(
			new InMemoryClientRegistrationRepository(registration),
			authorizedClients,
			"/v1/auth/*/callback");
		filter.setAuthorizationRequestRepository(authorizationRequests);
		filter.setAuthenticationManager(authentication -> loginResult());
		filter.setAuthenticationSuccessHandler((request, response, authentication) ->
			seenByHandler.set(authorizedClients
				.loadAuthorizedClient(REGISTRATION_ID, authentication, request)
				.getAccessToken()
				.getTokenValue()));

		filter.doFilter(callbackRequest(), new MockHttpServletResponse(), new MockFilterChain());

		assertThat(seenByHandler.get())
			.as("비어 있으면 해제할 토큰이 없어 모든 탈퇴가 WITHDRAWAL_FAILED로 끝난다")
			.isEqualTo(PROVIDER_ACCESS_TOKEN);
	}

	/** 인가 진입이 남겼을 쿠키까지 갖춘 콜백 요청. */
	private MockHttpServletRequest callbackRequest() {
		MockHttpServletResponse saved = new MockHttpServletResponse();
		authorizationRequests.saveAuthorizationRequest(
			authorizationRequest(), new MockHttpServletRequest(), saved);
		String setCookie = saved.getHeader(HttpHeaders.SET_COOKIE);

		MockHttpServletRequest request =
			new MockHttpServletRequest("GET", "/v1/auth/google/callback");
		request.setCookies(new Cookie("oauth2_auth_request",
			setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'))));
		request.setParameter("code", "authorization-code");
		request.setParameter("state", STATE);
		return request;
	}

	private OAuth2AuthorizationRequest authorizationRequest() {
		return OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri(registration.getProviderDetails().getAuthorizationUri())
			.clientId(registration.getClientId())
			.redirectUri(CALLBACK_URI)
			.state(STATE)
			.attributes(attributes -> {
				attributes.put("registration_id", REGISTRATION_ID);
				attributes.put(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, "77");
			})
			.build();
	}

	/** 토큰 교환과 사용자 조회는 이 테스트의 관심사가 아니라 결과만 준다. */
	private Authentication loginResult() {
		OAuth2AuthorizationRequest authorizationRequest = authorizationRequest();
		OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success("code")
			.redirectUri(CALLBACK_URI)
			.state(STATE)
			.build();
		return new OAuth2LoginAuthenticationToken(
			registration,
			new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse),
			new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES,
				Map.of("sub", "google-user-1", "email", "user@example.com"), "sub"),
			List.of(),
			new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, PROVIDER_ACCESS_TOKEN,
				Instant.now(), Instant.now().plusSeconds(3600)));
	}
}
