package com.pinlog.pinlogback.global.security.oauth;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.domain.auth.dto.OAuthUserInfo;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService.TokenPair;
import com.pinlog.pinlogback.domain.auth.service.SocialLoginService;
import com.pinlog.pinlogback.global.security.token.AuthCookies;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 공급자 인증이 끝난 뒤 회원을 확정하고 클라이언트로 돌려보낸다(API 명세 3.2).
 *
 * <p>정규화와 회원 생성을 사용자 정보 서비스가 아니라 여기서 하는 이유: openid scope를 쓰면
 * Spring이 {@code OidcUserService}를, 쓰지 않으면 {@code DefaultOAuth2UserService}를 태운다.
 * 성공 핸들러는 두 경우 모두 같은 {@code OAuth2AuthenticationToken}을 받으므로 분기가 생기지 않는다.
 *
 * <p><b>이 메서드는 필터 체인 안에서 돌아 {@code @RestControllerAdvice}를 타지 않는다.</b> 여기서
 * 예외가 새어 나가면 공통 envelope도, 명세가 정한 복귀 경로도 아닌 컨테이너 기본 500 페이지가
 * 나간다. 그래서 본문 전체를 감싸 실패 핸들러와 <b>같은 경로</b>로 보낸다 — 사용자는 어느 쪽이든
 * {@code /auth/callback?error=OAUTH_FAILED}로 돌아오고, 원인은 traceId로 로그에서 찾는다.
 */
@Slf4j
@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

	private final SocialLoginService socialLoginService;
	private final AuthTokenService authTokenService;
	private final AuthCookies authCookies;
	private final OAuthLoginFailureHandler failureHandler;
	private final String clientRedirectUri;

	public OAuthLoginSuccessHandler(
		SocialLoginService socialLoginService,
		AuthTokenService authTokenService,
		AuthCookies authCookies,
		OAuthLoginFailureHandler failureHandler,
		@Value("${pinlog.auth.client-redirect-uri}") String clientRedirectUri
	) {
		this.socialLoginService = socialLoginService;
		this.authTokenService = authTokenService;
		this.authCookies = authCookies;
		this.failureHandler = failureHandler;
		this.clientRedirectUri = clientRedirectUri;
	}

	@Override
	public void onAuthenticationSuccess(
		HttpServletRequest request,
		HttpServletResponse response,
		Authentication authentication
	) throws IOException, ServletException {
		try {
			issueSession(response, (OAuth2AuthenticationToken)authentication);
		} catch (RuntimeException e) {
			// 지원하지 않는 provider, 공급자 응답에 sub 없음, 토큰 발급 실패(Redis 장애 포함)가
			// 모두 여기로 온다. 실패 핸들러에 넘겨 로그와 복귀 경로를 한 곳에서 처리한다.
			failureHandler.onAuthenticationFailure(
				request, response, new InternalAuthenticationServiceException(e.getMessage(), e));
		}
	}

	private void issueSession(HttpServletResponse response, OAuth2AuthenticationToken token)
		throws IOException {
		OAuth2User principal = token.getPrincipal();
		OAuthUserInfo userInfo = OAuthUserInfo.from(
			token.getAuthorizedClientRegistrationId(), principal.getAttributes());

		TokenPair tokens = authTokenService.issue(loginTolerantOfFirstLoginRace(userInfo));
		// 리다이렉트는 응답을 커밋하므로 쿠키를 먼저 실어야 한다.
		authCookies.write(response, tokens.accessToken(), tokens.refreshToken());
		response.sendRedirect(clientRedirectUri);
	}

	/**
	 * 같은 소셜 계정의 <b>첫</b> 로그인이 동시에 두 번 들어오면 두 트랜잭션이 나란히 "계정 없음"을
	 * 보고 둘 다 INSERT해 {@code ux_social_account_provider_user}를 위반한다. 더블클릭으로도
	 * 재현된다.
	 *
	 * <p>진 쪽은 트랜잭션이 <b>통째로</b> 롤백되므로 회원 행도 함께 사라진다 — 고아 회원이 남지
	 * 않는다. 한 번만 다시 부르면 이긴 쪽이 커밋한 계정을 찾아 정상 로그인으로 수렴한다.
	 *
	 * <p>{@link SocialLoginService#login}이 {@code @Transactional}이고 여기는 그 <b>밖</b>이라,
	 * 재호출이 프록시를 타고 새 트랜잭션을 연다. 같은 트랜잭션 안에서 잡았다면 rollback-only라
	 * 재조회가 불가능했을 것이다(BI-14가 예외를 잡지 않고 {@code ON CONFLICT}로 간 이유).
	 */
	private Long loginTolerantOfFirstLoginRace(OAuthUserInfo userInfo) {
		try {
			return socialLoginService.login(userInfo);
		} catch (DataIntegrityViolationException e) {
			log.info("social account created concurrently, retrying once");
			return socialLoginService.login(userInfo);
		}
	}
}
