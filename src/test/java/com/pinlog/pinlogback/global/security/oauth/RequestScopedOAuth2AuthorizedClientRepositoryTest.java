package com.pinlog.pinlogback.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

@DisplayName("인가된 클라이언트 보관")
class RequestScopedOAuth2AuthorizedClientRepositoryTest {

	private static final String REGISTRATION_ID = "kakao";

	private final RequestScopedOAuth2AuthorizedClientRepository repository =
		new RequestScopedOAuth2AuthorizedClientRepository();
	private final Authentication principal = new TestingAuthenticationToken("user", null);

	@Test
	@DisplayName("같은 요청 안에서 저장한 것을 되읽는다")
	void savedClientIsReadableWithinTheSameRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		OAuth2AuthorizedClient client = authorizedClient();

		repository.saveAuthorizedClient(client, principal, request, new MockHttpServletResponse());

		assertThat(repository.<OAuth2AuthorizedClient>loadAuthorizedClient(
			REGISTRATION_ID, principal, request)).isSameAs(client);
	}

	@Test
	@DisplayName("세션을 만들지 않는다")
	void doesNotCreateSession() {
		// 세션을 만들면 SessionCreationPolicy.STATELESS 선언과 어긋난다(11 §2).
		MockHttpServletRequest request = new MockHttpServletRequest();

		repository.saveAuthorizedClient(
			authorizedClient(), principal, request, new MockHttpServletResponse());

		assertThat(request.getSession(false)).isNull();
	}

	@Test
	@DisplayName("다른 요청에서는 보이지 않는다")
	void doesNotLeakAcrossRequests() {
		MockHttpServletRequest saved = new MockHttpServletRequest();
		repository.saveAuthorizedClient(
			authorizedClient(), principal, saved, new MockHttpServletResponse());

		assertThat(repository.<OAuth2AuthorizedClient>loadAuthorizedClient(
			REGISTRATION_ID, principal, new MockHttpServletRequest())).isNull();
	}

	@Test
	@DisplayName("등록 id가 다르면 돌려주지 않는다")
	void doesNotAnswerForAnotherRegistration() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		repository.saveAuthorizedClient(
			authorizedClient(), principal, request, new MockHttpServletResponse());

		assertThat(repository.<OAuth2AuthorizedClient>loadAuthorizedClient(
			"google", principal, request)).isNull();
	}

	private OAuth2AuthorizedClient authorizedClient() {
		ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
			.clientId("client")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("https://pinlog.example/api/core/v1/auth/kakao/callback")
			.authorizationUri("https://kauth.kakao.com/oauth/authorize")
			.tokenUri("https://kauth.kakao.com/oauth/token")
			.build();
		OAuth2AccessToken accessToken = new OAuth2AccessToken(
			OAuth2AccessToken.TokenType.BEARER, "provider-access-token",
			Instant.now(), Instant.now().plusSeconds(3600));
		return new OAuth2AuthorizedClient(registration, "provider-user-id", accessToken);
	}
}
