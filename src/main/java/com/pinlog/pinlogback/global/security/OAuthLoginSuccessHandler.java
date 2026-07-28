package com.pinlog.pinlogback.global.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.domain.auth.dto.OAuthUserInfo;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService.TokenPair;
import com.pinlog.pinlogback.domain.auth.service.SocialLoginService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 공급자 인증이 끝난 뒤 회원을 확정하고 클라이언트로 돌려보낸다(API 명세 3.2).
 *
 * <p>정규화와 회원 생성을 사용자 정보 서비스가 아니라 여기서 하는 이유: openid scope를 쓰면
 * Spring이 {@code OidcUserService}를, 쓰지 않으면 {@code DefaultOAuth2UserService}를 태운다.
 * 성공 핸들러는 두 경우 모두 같은 {@code OAuth2AuthenticationToken}을 받으므로 분기가 생기지 않는다.
 */
@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

	private final SocialLoginService socialLoginService;
	private final AuthTokenService authTokenService;
	private final AuthCookies authCookies;
	private final String clientRedirectUri;

	public OAuthLoginSuccessHandler(
		SocialLoginService socialLoginService,
		AuthTokenService authTokenService,
		AuthCookies authCookies,
		@Value("${pinlog.auth.client-redirect-uri}") String clientRedirectUri
	) {
		this.socialLoginService = socialLoginService;
		this.authTokenService = authTokenService;
		this.authCookies = authCookies;
		this.clientRedirectUri = clientRedirectUri;
	}

	@Override
	public void onAuthenticationSuccess(
		HttpServletRequest request,
		HttpServletResponse response,
		Authentication authentication
	) throws IOException {
		OAuth2AuthenticationToken token = (OAuth2AuthenticationToken)authentication;
		OAuth2User principal = token.getPrincipal();

		OAuthUserInfo userInfo = OAuthUserInfo.from(
			token.getAuthorizedClientRegistrationId(), principal.getAttributes());
		Long memberId = socialLoginService.login(userInfo);

		TokenPair tokens = authTokenService.issue(memberId);
		// 리다이렉트는 응답을 커밋하므로 쿠키를 먼저 실어야 한다.
		authCookies.write(response, tokens.accessToken(), tokens.refreshToken());
		response.sendRedirect(clientRedirectUri);
	}
}
