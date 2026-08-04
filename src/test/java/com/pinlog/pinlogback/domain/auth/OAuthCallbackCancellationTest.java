package com.pinlog.pinlogback.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import com.pinlog.pinlogback.global.security.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.pinlog.pinlogback.global.security.oauth.WithdrawalAwareAuthorizationRequestResolver;
import com.pinlog.pinlogback.support.CoreApiFixtures;

import jakarta.servlet.http.Cookie;

/**
 * 공급자 화면에서 사용자가 거절했을 때 어느 어휘로 돌아오는지 고정한다(BD-48 §④).
 *
 * <p><b>필터 체인을 실제로 태워야 하는 항목이다.</b> 실패 핸들러가 왕복의 의도를 읽으려면
 * {@code OAuth2LoginAuthenticationFilter}가 인가 요청을 <b>거절을 던지기 전에</b> 소비해야 하는데,
 * 그 순서는 Spring 내부에 있다. 핸들러만 단위로 부르면 그 전제를 검증하지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("공급자 인증 거절")
class OAuthCallbackCancellationTest extends CoreApiFixtures {

	private static final String CALLBACK_PATH = "/v1/auth/google/callback";
	private static final String STATE = "state-value";

	@Test
	@DisplayName("탈퇴 왕복에서 거절하면 WITHDRAWAL_CANCELLED로 돌아온다")
	void denyingDuringWithdrawalIsCancellation() throws Exception {
		mockMvc.perform(get(CALLBACK_PATH)
				.param("error", "access_denied")
				.param("state", STATE)
				.cookie(authorizationRequestCookie(true)))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string(HttpHeaders.LOCATION,
				Matchers.containsString("error=WITHDRAWAL_CANCELLED")));
	}

	@Test
	@DisplayName("로그인 왕복에서 거절하면 OAUTH_FAILED 그대로다")
	void denyingDuringLoginKeepsItsCode() throws Exception {
		mockMvc.perform(get(CALLBACK_PATH)
				.param("error", "access_denied")
				.param("state", STATE)
				.cookie(authorizationRequestCookie(false)))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string(HttpHeaders.LOCATION,
				Matchers.containsString("error=OAUTH_FAILED")));
	}

	/** 인가 진입이 남겼을 쿠키를 같은 방식으로 만든다. */
	private Cookie authorizationRequestCookie(boolean withdrawal) {
		OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
			.clientId("client")
			.redirectUri("http://localhost/api/core" + CALLBACK_PATH)
			.state(STATE)
			.attributes(attributes -> attributes.put("registration_id", "google"));
		if (withdrawal) {
			builder.attributes(attributes -> attributes.put(
				WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, 77L));
		}

		MockHttpServletResponse saved = new MockHttpServletResponse();
		new CookieOAuth2AuthorizationRequestRepository()
			.saveAuthorizationRequest(builder.build(), new MockHttpServletRequest(), saved);
		String setCookie = saved.getHeader(HttpHeaders.SET_COOKIE);
		return new Cookie("oauth2_auth_request",
			setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
	}
}
