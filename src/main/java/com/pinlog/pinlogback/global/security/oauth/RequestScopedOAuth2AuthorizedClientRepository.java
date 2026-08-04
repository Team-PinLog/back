package com.pinlog.pinlogback.global.security.oauth;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 인가된 클라이언트(공급자 access token 포함)를 <b>요청 하나 동안만</b> 들고 있는다.
 *
 * <p>Spring 기본값인 {@code HttpSessionOAuth2AuthorizedClientRepository}는 {@code HttpSession}을
 * 만든다. 우리는 {@code SessionCreationPolicy.STATELESS}를 선언했으므로(11 §2) 인가 요청 저장소를
 * 쿠키로 바꾼 것과 같은 이유로 이것도 바꾼다.
 *
 * <p><b>요청 범위면 충분하다.</b> 공급자 토큰이 필요한 곳은 콜백 성공 처리 한 군데이고
 * ({@code OAuth2LoginAuthenticationFilter}가 저장한 뒤 같은 요청에서 성공 핸들러가 돈다), 그 뒤로는
 * 쓰지 않는다. 오래 들고 있을수록 <b>보관해야 할 자격증명이 늘 뿐</b>이라 BD-48이 토큰을 저장하지
 * 않기로 한 결정과도 어긋난다.
 *
 * <p>인증된 회원을 키로 삼지 않는 것도 같은 이유다 — 한 요청에는 인가 왕복이 하나뿐이라
 * registrationId만으로 충돌하지 않는다.
 */
@Component
public class RequestScopedOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

	private static final String ATTRIBUTE_PREFIX =
		RequestScopedOAuth2AuthorizedClientRepository.class.getName() + ".";

	@SuppressWarnings("unchecked")
	@Override
	public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
		String clientRegistrationId, @Nullable Authentication principal, HttpServletRequest request) {
		return (T)request.getAttribute(ATTRIBUTE_PREFIX + clientRegistrationId);
	}

	@Override
	public void saveAuthorizedClient(
		OAuth2AuthorizedClient authorizedClient,
		@Nullable Authentication principal,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		request.setAttribute(
			ATTRIBUTE_PREFIX + authorizedClient.getClientRegistration().getRegistrationId(),
			authorizedClient);
	}

	@Override
	public void removeAuthorizedClient(
		String clientRegistrationId,
		@Nullable Authentication principal,
		HttpServletRequest request,
		HttpServletResponse response
	) {
		request.removeAttribute(ATTRIBUTE_PREFIX + clientRegistrationId);
	}
}
