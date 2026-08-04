package com.pinlog.pinlogback.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService.TokenPair;
import com.pinlog.pinlogback.domain.auth.service.SocialLoginService;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.exception.WithdrawalAccountMismatchException;
import com.pinlog.pinlogback.domain.member.service.WithdrawalCompletionService;
import com.pinlog.pinlogback.global.security.token.AuthCookies;
import com.pinlog.pinlogback.global.security.token.JwtProperties;

import jakarta.servlet.http.Cookie;

/**
 * 콜백이 로그인과 탈퇴를 가르는 지점(BD-48 §③·§④).
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. 검증 대상은 "인가 요청 attributes를 보고 어느 쪽으로 가는가"와
 * "실패가 어떤 {@code ?error=}로 돌아오는가"뿐이고, 둘 다 핸들러를 손으로 조립하면 관찰된다.
 */
@DisplayName("탈퇴 콜백 분기")
class OAuthCallbackWithdrawalBranchTest {

	private static final Long MEMBER_ID = 77L;
	private static final String PROVIDER_USER_ID = "google-user-1";
	private static final String PROVIDER_ACCESS_TOKEN = "provider-access-token";
	private static final String CLIENT_REDIRECT_URI = "https://pinlog.example/auth/callback";

	private final SocialLoginService socialLoginService = mock(SocialLoginService.class);
	private final AuthTokenService authTokenService = mock(AuthTokenService.class);
	private final WithdrawalCompletionService completionService =
		mock(WithdrawalCompletionService.class);
	private final CookieOAuth2AuthorizationRequestRepository authorizationRequests =
		new CookieOAuth2AuthorizationRequestRepository();
	private final RequestScopedOAuth2AuthorizedClientRepository authorizedClients =
		new RequestScopedOAuth2AuthorizedClientRepository();
	private final AuthCookies authCookies = new AuthCookies(
		new JwtProperties(null, Duration.ofMinutes(30), Duration.ofDays(7),
			Duration.ofMinutes(5), "pinlog"),
		"/api/core");
	private final OAuthLoginFailureHandler failureHandler =
		new OAuthLoginFailureHandler(CLIENT_REDIRECT_URI);
	private final OAuthLoginSuccessHandler successHandler = new OAuthLoginSuccessHandler(
		socialLoginService, authTokenService, completionService, authorizedClients,
		authCookies, failureHandler, CLIENT_REDIRECT_URI);

	@Test
	@DisplayName("탈퇴 왕복이면 세션을 발급하지 않고 해제·삭제로 간다")
	void withdrawalRoundTripCompletesWithdrawal() throws Exception {
		MockHttpServletRequest request = callbackOf(withdrawalAuthorizationRequest());
		MockHttpServletResponse response = new MockHttpServletResponse();
		authorizedClients.saveAuthorizedClient(authorizedClient(), null, request, response);

		successHandler.onAuthenticationSuccess(request, response, authentication());

		verify(completionService).complete(
			eq(MEMBER_ID), eq(SocialProvider.GOOGLE), eq(PROVIDER_USER_ID), eq(PROVIDER_ACCESS_TOKEN));
		verifyNoInteractions(socialLoginService, authTokenService);
	}

	@Test
	@DisplayName("탈퇴가 끝나면 쿠키를 지우고 표시 없이 복귀 경로로 보낸다")
	void withdrawalClearsCookiesAndRedirects() throws Exception {
		MockHttpServletRequest request = callbackOf(withdrawalAuthorizationRequest());
		MockHttpServletResponse response = new MockHttpServletResponse();
		authorizedClients.saveAuthorizedClient(authorizedClient(), null, request, response);

		successHandler.onAuthenticationSuccess(request, response, authentication());

		assertThat(expiredCookieNames(response)).contains(
			AuthCookies.ACCESS_TOKEN, AuthCookies.REFRESH_TOKEN, AuthCookies.LOGGED_IN);
		// 08 §3.6.2가 성공에 표시를 두지 않기로 정했다. 쿠키가 만료된 채 착지하는 것이 신호다.
		// 프론트의 콜백 스키마도 error 외의 쿼리를 버리므로 표시를 붙여도 도달하지 않는다.
		assertThat(response.getRedirectedUrl()).isEqualTo(CLIENT_REDIRECT_URI);
	}

	@Test
	@DisplayName("해제가 실패하면 WITHDRAWAL_FAILED로 돌려보내고 쿠키를 지우지 않는다")
	void unlinkFailureRedirectsWithFailedCode() throws Exception {
		MockHttpServletRequest request = callbackOf(withdrawalAuthorizationRequest());
		MockHttpServletResponse response = new MockHttpServletResponse();
		authorizedClients.saveAuthorizedClient(authorizedClient(), null, request, response);
		doThrow(new SocialUnlinkException("boom", new IllegalStateException(), false))
			.when(completionService).complete(any(), any(), any(), any());

		successHandler.onAuthenticationSuccess(request, response, authentication());

		assertThat(response.getRedirectedUrl())
			.isEqualTo(CLIENT_REDIRECT_URI + "?error=WITHDRAWAL_FAILED");
		// 탈퇴가 확정되지 않았으므로 로그인 상태를 유지한다 — 사용자가 다시 시도할 수 있어야 한다.
		assertThat(expiredCookieNames(response)).isEmpty();
	}

	@Test
	@DisplayName("다른 계정으로 인증하면 WITHDRAWAL_ACCOUNT_MISMATCH로 돌려보낸다")
	void accountMismatchHasItsOwnCode() throws Exception {
		MockHttpServletRequest request = callbackOf(withdrawalAuthorizationRequest());
		MockHttpServletResponse response = new MockHttpServletResponse();
		authorizedClients.saveAuthorizedClient(authorizedClient(), null, request, response);
		doThrow(new WithdrawalAccountMismatchException(MEMBER_ID))
			.when(completionService).complete(any(), any(), any(), any());

		successHandler.onAuthenticationSuccess(request, response, authentication());

		assertThat(response.getRedirectedUrl())
			.isEqualTo(CLIENT_REDIRECT_URI + "?error=WITHDRAWAL_ACCOUNT_MISMATCH");
	}

	@Test
	@DisplayName("탈퇴 왕복에서 사용자가 취소하면 WITHDRAWAL_CANCELLED로 돌려보낸다")
	void providerDenialDuringWithdrawalIsCancellation() throws Exception {
		// 공급자는 RFC 6749 §4.1.2.1의 access_denied로 알린다. 로그인 취소와 같은 코드를 쓰면
		// 프론트가 "탈퇴가 취소됐다"를 말할 수 없다.
		MockHttpServletRequest request = callbackOf(withdrawalAuthorizationRequest());
		MockHttpServletResponse response = new MockHttpServletResponse();

		failureHandler.onAuthenticationFailure(request, response,
			new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "denied"));

		assertThat(response.getRedirectedUrl())
			.isEqualTo(CLIENT_REDIRECT_URI + "?error=WITHDRAWAL_CANCELLED");
	}

	@Test
	@DisplayName("로그인 왕복의 실패는 그대로 OAUTH_FAILED다")
	void loginFailureKeepsItsCode() throws Exception {
		MockHttpServletRequest request = callbackOf(loginAuthorizationRequest());
		MockHttpServletResponse response = new MockHttpServletResponse();

		failureHandler.onAuthenticationFailure(request, response,
			new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "denied"));

		assertThat(response.getRedirectedUrl()).isEqualTo(CLIENT_REDIRECT_URI + "?error=OAUTH_FAILED");
	}

	@Test
	@DisplayName("티켓이 없는 왕복은 평범한 로그인이다")
	void withoutWithdrawalAttributeItIsALogin() throws Exception {
		MockHttpServletRequest request = callbackOf(loginAuthorizationRequest());
		MockHttpServletResponse response = new MockHttpServletResponse();
		given(socialLoginService.login(any())).willReturn(MEMBER_ID);
		given(authTokenService.issue(MEMBER_ID)).willReturn(new TokenPair("access", "refresh"));

		successHandler.onAuthenticationSuccess(request, response, authentication());

		verify(completionService, never()).complete(any(), any(), any(), any());
		verify(socialLoginService).login(any());
		assertThat(response.getRedirectedUrl()).isEqualTo(CLIENT_REDIRECT_URI);
	}

	private List<String> expiredCookieNames(MockHttpServletResponse response) {
		return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
			.filter(header -> header.contains("Max-Age=0"))
			.map(header -> header.substring(0, header.indexOf('=')))
			.toList();
	}

	/** 인가 요청 쿠키를 만들고, 필터가 소비한 것과 같은 상태의 콜백 요청을 돌려준다. */
	private MockHttpServletRequest callbackOf(OAuth2AuthorizationRequest authorizationRequest) {
		MockHttpServletResponse saved = new MockHttpServletResponse();
		authorizationRequests.saveAuthorizationRequest(
			authorizationRequest, new MockHttpServletRequest(), saved);

		String setCookie = saved.getHeader(HttpHeaders.SET_COOKIE);
		String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
		MockHttpServletRequest callback = new MockHttpServletRequest();
		callback.setCookies(new Cookie("oauth2_auth_request", value));
		authorizationRequests.removeAuthorizationRequest(callback, new MockHttpServletResponse());
		return callback;
	}

	private OAuth2AuthorizationRequest withdrawalAuthorizationRequest() {
		return OAuth2AuthorizationRequest.from(loginAuthorizationRequest())
			.attributes(attributes -> attributes.put(
				WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, MEMBER_ID))
			.build();
	}

	private OAuth2AuthorizationRequest loginAuthorizationRequest() {
		return OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
			.clientId("client")
			.redirectUri("https://pinlog.example/api/core/v1/auth/google/callback")
			.state("state-value")
			.build();
	}

	private OAuth2AuthenticationToken authentication() {
		DefaultOAuth2User principal = new DefaultOAuth2User(
			AuthorityUtils.NO_AUTHORITIES,
			Map.of("sub", PROVIDER_USER_ID, "email", "user@example.com"),
			"sub");
		return new OAuth2AuthenticationToken(
			principal, AuthorityUtils.NO_AUTHORITIES, SocialProvider.GOOGLE.registrationId());
	}

	private OAuth2AuthorizedClient authorizedClient() {
		ClientRegistration registration =
			ClientRegistration.withRegistrationId(SocialProvider.GOOGLE.registrationId())
				.clientId("client")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("https://pinlog.example/api/core/v1/auth/google/callback")
				.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
				.tokenUri("https://oauth2.googleapis.com/token")
				.build();
		return new OAuth2AuthorizedClient(registration, PROVIDER_USER_ID, new OAuth2AccessToken(
			OAuth2AccessToken.TokenType.BEARER, PROVIDER_ACCESS_TOKEN,
			Instant.now(), Instant.now().plusSeconds(3600)));
	}
}
