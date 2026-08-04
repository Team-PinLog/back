package com.pinlog.pinlogback.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

import com.pinlog.pinlogback.global.security.token.JwtKeyProvider;
import com.pinlog.pinlogback.global.security.token.JwtProperties;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 탈퇴 인가 왕복의 진입 검증(BD-48 §②·§③).
 *
 * <p>Spring 컨텍스트를 띄우지 않는다 — 검증 대상이 "티켓이 유효한가"와 "그 결과가 어디에 실리는가"
 * 둘뿐이고, 둘 다 위임 resolver를 손으로 끼워 넣으면 관찰된다.
 */
@DisplayName("탈퇴 인가 진입")
class WithdrawalAwareAuthorizationRequestResolverTest {

	private static final Long MEMBER_ID = 77L;

	private final JwtProperties properties = new JwtProperties(
		null, Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(5), "pinlog");
	private final JwtTokenProvider tokenProvider =
		new JwtTokenProvider(properties, new JwtKeyProvider(properties, new MockEnvironment()));
	private final WithdrawalAwareAuthorizationRequestResolver resolver =
		new WithdrawalAwareAuthorizationRequestResolver(new StubResolver(), tokenProvider);

	@Test
	@DisplayName("티켓은 공급자에게 나가는 인가 URL에 실리지 않는다")
	void ticketNeverReachesTheProvider() {
		// 실제 위임 구현으로 확인해야 하는 항목이다. 스텁은 이 성질을 증명하지 못한다.
		// 티켓이 인가 URL에 실리면 공급자 서버 로그와 리퍼러에 계정 삭제 권한이 남는다.
		DefaultOAuth2AuthorizationRequestResolver delegate = new DefaultOAuth2AuthorizationRequestResolver(
			new InMemoryClientRegistrationRepository(googleRegistration()),
			OAuthEndpointPaths.AUTHORIZATION_BASE_URI);
		delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
		WithdrawalAwareAuthorizationRequestResolver real =
			new WithdrawalAwareAuthorizationRequestResolver(delegate, tokenProvider);
		String ticket = tokenProvider.issueWithdrawalTicket(MEMBER_ID);

		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET", OAuthEndpointPaths.AUTHORIZATION_BASE_URI + "/google");
		request.setParameter(WithdrawalAwareAuthorizationRequestResolver.TICKET_PARAMETER, ticket);
		OAuth2AuthorizationRequest resolved = real.resolve(request);

		assertThat(resolved).isNotNull();
		assertThat(resolved.getAuthorizationRequestUri()).doesNotContain(ticket);
		assertThat(resolved.getAdditionalParameters())
			.doesNotContainKey(WithdrawalAwareAuthorizationRequestResolver.TICKET_PARAMETER);
		// 그러면서 의도는 우리 쪽에 남아 있어야 한다.
		assertThat(resolved.getAttributes())
			.containsEntry(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, String.valueOf(MEMBER_ID));
	}

	@Test
	@DisplayName("attributes에 의도를 얹어도 PKCE가 살아남는다")
	void addingTheIntentDoesNotDropPkce() {
		// 우리는 위임 결과를 복사해 attributes를 더한다. 그 과정에서 code_verifier가 떨어지면
		// 인가 자체는 성공하고 토큰 교환만 invalid_grant으로 죽는다 — 탈퇴가 통째로 불가능해지는데
		// 증상은 콜백 실패 하나로만 보인다.
		DefaultOAuth2AuthorizationRequestResolver delegate = new DefaultOAuth2AuthorizationRequestResolver(
			new InMemoryClientRegistrationRepository(googleRegistration()),
			OAuthEndpointPaths.AUTHORIZATION_BASE_URI);
		delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
		WithdrawalAwareAuthorizationRequestResolver real =
			new WithdrawalAwareAuthorizationRequestResolver(delegate, tokenProvider);

		MockHttpServletRequest login = new MockHttpServletRequest(
			"GET", OAuthEndpointPaths.AUTHORIZATION_BASE_URI + "/google");
		MockHttpServletRequest withdrawal = new MockHttpServletRequest(
			"GET", OAuthEndpointPaths.AUTHORIZATION_BASE_URI + "/google");
		withdrawal.setParameter(WithdrawalAwareAuthorizationRequestResolver.TICKET_PARAMETER,
			tokenProvider.issueWithdrawalTicket(MEMBER_ID));

		OAuth2AuthorizationRequest plain = real.resolve(login);
		OAuth2AuthorizationRequest withIntent = real.resolve(withdrawal);

		assertThat(plain).isNotNull();
		assertThat(withIntent).isNotNull();
		assertThat(withIntent.getAttributes())
			.as("로그인 왕복이 갖는 것을 탈퇴 왕복도 그대로 가져야 한다")
			.containsKey(PkceParameterNames.CODE_VERIFIER);
		assertThat(withIntent.getAdditionalParameters())
			.containsKey(PkceParameterNames.CODE_CHALLENGE)
			.containsKey(PkceParameterNames.CODE_CHALLENGE_METHOD);
		assertThat(plain.getAttributes()).containsKey(PkceParameterNames.CODE_VERIFIER);
	}

	@Test
	@DisplayName("Google 탈퇴 왕복은 refresh token을 받도록 요청한다")
	void googleWithdrawalAsksForARefreshToken() {
		// Google에서 승인을 지우려면 access token이 아니라 refresh token을 폐기해야 한다.
		// access_type=offline이 없으면 refresh token 자체가 오지 않고, Google은 그것을 첫 인가에만
		// 주므로 prompt=consent가 있어야 탈퇴 시점에 확실히 받는다.
		OAuth2AuthorizationRequest resolved = realResolver(googleRegistration())
			.resolve(withdrawalRequest("google"));

		assertThat(resolved).isNotNull();
		assertThat(resolved.getAdditionalParameters())
			.containsEntry("access_type", "offline")
			.containsEntry("prompt", "consent");
	}

	@Test
	@DisplayName("Google 로그인 진입에는 그 파라미터가 붙지 않는다")
	void googleLoginIsNotAskedForARefreshToken() {
		// 로그인마다 동의 화면이 뜨는 것은 이 작업이 감수한 대가가 아니다. 탈퇴 왕복에만 붙인다.
		MockHttpServletRequest login = new MockHttpServletRequest(
			"GET", OAuthEndpointPaths.AUTHORIZATION_BASE_URI + "/google");

		OAuth2AuthorizationRequest resolved = realResolver(googleRegistration()).resolve(login);

		assertThat(resolved).isNotNull();
		assertThat(resolved.getAdditionalParameters())
			.doesNotContainKey("access_type")
			.doesNotContainKey("prompt");
	}

	@Test
	@DisplayName("Kakao 탈퇴 왕복에는 붙이지 않는다 — Google 전용 파라미터다")
	void otherProvidersAreUntouched() {
		// Kakao·Naver는 연결 단위 API라 access token으로 이미 끊긴다. 남의 인가 요청에 Google
		// 전용 파라미터를 실으면 공급자가 거절할 수 있다.
		OAuth2AuthorizationRequest resolved = realResolver(kakaoRegistration())
			.resolve(withdrawalRequest("kakao"));

		assertThat(resolved).isNotNull();
		assertThat(resolved.getAdditionalParameters())
			.doesNotContainKey("access_type")
			.doesNotContainKey("prompt");
	}

	private WithdrawalAwareAuthorizationRequestResolver realResolver(ClientRegistration registration) {
		DefaultOAuth2AuthorizationRequestResolver delegate = new DefaultOAuth2AuthorizationRequestResolver(
			new InMemoryClientRegistrationRepository(registration),
			OAuthEndpointPaths.AUTHORIZATION_BASE_URI);
		delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
		return new WithdrawalAwareAuthorizationRequestResolver(delegate, tokenProvider);
	}

	private MockHttpServletRequest withdrawalRequest(String registrationId) {
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET", OAuthEndpointPaths.AUTHORIZATION_BASE_URI + "/" + registrationId);
		request.setParameter(WithdrawalAwareAuthorizationRequestResolver.TICKET_PARAMETER,
			tokenProvider.issueWithdrawalTicket(MEMBER_ID));
		return request;
	}

	private ClientRegistration kakaoRegistration() {
		return ClientRegistration.withRegistrationId("kakao")
			.clientId("client")
			.clientSecret("secret")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("https://pinlog.example/api/core/v1/auth/{registrationId}/callback")
			.authorizationUri("https://kauth.kakao.com/oauth/authorize")
			.tokenUri("https://kauth.kakao.com/oauth/token")
			.userInfoUri("https://kapi.kakao.com/v2/user/me")
			.userNameAttributeName("id")
			.scope("account_email")
			.build();
	}

	private ClientRegistration googleRegistration() {
		return ClientRegistration.withRegistrationId("google")
			.clientId("client")
			.clientSecret("secret")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("https://pinlog.example/api/core/v1/auth/{registrationId}/callback")
			.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
			.tokenUri("https://oauth2.googleapis.com/token")
			.userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
			.userNameAttributeName("sub")
			.scope("openid", "email")
			.build();
	}

	@Test
	@DisplayName("티켓이 없으면 평범한 로그인 인가 요청 그대로다")
	void withoutTicketPassesThrough() {
		OAuth2AuthorizationRequest resolved = resolver.resolve(request(null));

		assertThat(resolved).isNotNull();
		assertThat(resolved.getAttributes())
			.doesNotContainKey(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID);
	}

	@Test
	@DisplayName("유효한 티켓이면 탈퇴 대상 회원을 attributes에 싣는다")
	void validTicketCarriesMemberIdInAttributes() {
		String ticket = tokenProvider.issueWithdrawalTicket(MEMBER_ID);

		OAuth2AuthorizationRequest resolved = resolver.resolve(request(ticket));

		assertThat(resolved).isNotNull();
		assertThat(resolved.getAttributes())
			.containsEntry(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, String.valueOf(MEMBER_ID));
		// 위임이 만든 PKCE·state가 살아 있어야 한다 — 새로 만들면 콜백이 깨진다.
		assertThat(resolved.getState()).isEqualTo("delegated-state");
	}

	@Test
	@DisplayName("Access 토큰을 티켓 자리에 넣으면 거부된다")
	void accessTokenIsNotAWithdrawalTicket() {
		// 용도 구분이 없으면 30분짜리 Access가 계정 삭제 왕복을 시작할 권한이 된다.
		String accessToken = tokenProvider.issueAccessToken(MEMBER_ID);

		assertThat(resolver.resolve(request(accessToken))).isNull();
	}

	@Test
	@DisplayName("서명이 깨진 티켓이면 인가 요청을 만들지 않는다")
	void tamperedTicketIsRejected() {
		assertThat(resolver.resolve(request("not-a-jwt"))).isNull();
	}

	private HttpServletRequest request(String ticket) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/auth/authorize/kakao");
		if (ticket != null) {
			request.setParameter(WithdrawalAwareAuthorizationRequestResolver.TICKET_PARAMETER, ticket);
		}
		return request;
	}

	/** 위임 resolver. 실제 구현은 ClientRegistrationRepository가 필요해 여기서는 결과만 흉내 낸다. */
	private static final class StubResolver implements OAuth2AuthorizationRequestResolver {

		@Override
		public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
			return OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("https://kauth.kakao.com/oauth/authorize")
				.clientId("client")
				.redirectUri("https://pinlog.example/api/core/v1/auth/kakao/callback")
				.state("delegated-state")
				.attributes(attributes -> attributes.put("registration_id", "kakao"))
				.build();
		}

		@Override
		public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
			return resolve(request);
		}
	}
}
