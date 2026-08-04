package com.pinlog.pinlogback.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

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
			.containsEntry(WithdrawalAwareAuthorizationRequestResolver.WITHDRAWAL_MEMBER_ID, MEMBER_ID);
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
