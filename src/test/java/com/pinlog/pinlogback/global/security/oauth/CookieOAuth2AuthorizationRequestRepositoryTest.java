package com.pinlog.pinlogback.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.Cookie;

@DisplayName("인가 요청 쿠키 보관")
class CookieOAuth2AuthorizationRequestRepositoryTest {

	private final CookieOAuth2AuthorizationRequestRepository repository =
		new CookieOAuth2AuthorizationRequestRepository();

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
			.containsEntry(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, 77L);
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
			.containsEntry(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, 77L);
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
				WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, 77L))
			.build();
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
