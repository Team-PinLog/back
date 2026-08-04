package com.pinlog.pinlogback.global.security.oauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.pinlog.pinlogback.domain.auth.dto.OAuthUserInfo;
import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService;
import com.pinlog.pinlogback.domain.auth.service.AuthTokenService.TokenPair;
import com.pinlog.pinlogback.domain.auth.service.SocialLoginService;
import com.pinlog.pinlogback.domain.member.exception.WithdrawalAccountMismatchException;
import com.pinlog.pinlogback.domain.member.service.WithdrawalCompletionService;
import com.pinlog.pinlogback.global.security.token.AuthCookies;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 공급자 인증이 끝난 뒤 회원을 확정하고 클라이언트로 돌려보낸다(API 명세 3.2).
 *
 * <p><b>같은 콜백이 두 흐름을 받는다.</b> 로그인이면 세션을 발급하고, 탈퇴 왕복이면 연결 해제와
 * 소프트 삭제로 간다(BD-48). 어느 쪽인지는 인가 요청 {@code attributes}가 정하며, 그 값을 넣는 것은
 * {@link WithdrawalAwareAuthorizationRequestResolver}다 — 콜백 경로를 나누지 않은 이유는
 * {@code redirect-uri}를 공급자 콘솔 양쪽에서 바꿔야 하기 때문이다.
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
	private final WithdrawalCompletionService withdrawalCompletionService;
	private final OAuth2AuthorizedClientRepository authorizedClients;
	private final AuthCookies authCookies;
	private final OAuthLoginFailureHandler failureHandler;
	private final String clientRedirectUri;

	public OAuthLoginSuccessHandler(
		SocialLoginService socialLoginService,
		AuthTokenService authTokenService,
		WithdrawalCompletionService withdrawalCompletionService,
		OAuth2AuthorizedClientRepository authorizedClients,
		AuthCookies authCookies,
		OAuthLoginFailureHandler failureHandler,
		@Value("${pinlog.auth.client-redirect-uri}") String clientRedirectUri
	) {
		this.socialLoginService = socialLoginService;
		this.authTokenService = authTokenService;
		this.withdrawalCompletionService = withdrawalCompletionService;
		this.authorizedClients = authorizedClients;
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
		OAuth2AuthenticationToken token = (OAuth2AuthenticationToken)authentication;

		Optional<Long> withdrawing =
			WithdrawalAwareAuthorizationRequestResolver.withdrawalMemberId(request);
		if (withdrawing.isPresent()) {
			completeWithdrawal(request, response, token, withdrawing.get());
			return;
		}

		try {
			issueSession(response, token);
		} catch (RuntimeException e) {
			// 지원하지 않는 provider, 공급자 응답에 sub 없음, 토큰 발급 실패(Redis 장애 포함)가
			// 모두 여기로 온다. 실패 핸들러에 넘겨 로그와 복귀 경로를 한 곳에서 처리한다.
			//
			// 어느 단계에서 터졌는지는 별도 라벨을 두지 않고 예외 타입·메시지가 말하게 한다 —
			// 정규화는 "필수 속성이 없다"를 든 IllegalStateException, 가입 경합은
			// DataIntegrityViolationException, 발급은 Redis 예외다. 실패 핸들러가 타입을 함께
			// 남기므로 라벨을 더해도 정보가 늘지 않는다.
			failureHandler.onAuthenticationFailure(
				request, response, new InternalAuthenticationServiceException(e.getMessage(), e));
		}
	}

	/**
	 * 탈퇴 왕복의 나머지 절반(BD-48). 세션을 발급하지 않는다 — 이 인증은 <b>공급자 토큰을 받기
	 * 위한 것</b>이고, 끝나면 회원이 사라진다.
	 *
	 * <p>회원 식별자는 인가 요청 {@code attributes}에서 온다. 쿠키로 다시 식별하면 왕복 중
	 * Access(30분)가 만료됐을 때 탈퇴를 완료할 수 없다.
	 *
	 * <p>실패는 예외 종류에 따라 다른 코드로 돌려보내고 <b>쿠키를 지우지 않는다</b> — 탈퇴가
	 * 확정되지 않았으므로 사용자가 다시 시도할 수 있어야 한다.
	 */
	private void completeWithdrawal(
		HttpServletRequest request,
		HttpServletResponse response,
		OAuth2AuthenticationToken token,
		Long memberId
	) throws IOException {
		String registrationId = token.getAuthorizedClientRegistrationId();
		try {
			OAuthUserInfo userInfo =
				OAuthUserInfo.from(registrationId, token.getPrincipal().getAttributes());
			withdrawalCompletionService.complete(
				memberId, userInfo.provider(), userInfo.providerUserId(),
				accessTokenOf(request, token, registrationId));
		} catch (RuntimeException e) {
			log.warn("withdrawal failed after provider authorization: memberId={}, provider={}, [{}] {}",
				memberId, registrationId, e.getClass().getSimpleName(), e.getMessage(), e);
			response.sendRedirect(failureHandler.redirectWith(errorCodeOf(e)));
			return;
		}

		log.info("withdrawal completed: memberId={}, provider={}", memberId, registrationId);
		// 리다이렉트는 응답을 커밋하므로 쿠키를 먼저 실어야 한다.
		authCookies.clear(response);
		// 성공에는 표시를 붙이지 않는다(08 §3.6.2). 쿠키가 만료된 채로 착지하므로 클라이언트의
		// 기존 앱 시작 흐름이 그대로 로그인 화면으로 보낸다 — 별도 신호가 필요 없다.
		response.sendRedirect(clientRedirectUri);
	}

	/** 공급자 토큰은 {@code OAuth2AuthenticationToken}에 실리지 않는다 — 저장소에서 꺼낸다. */
	private String accessTokenOf(
		HttpServletRequest request, OAuth2AuthenticationToken token, String registrationId) {
		OAuth2AuthorizedClient client =
			authorizedClients.loadAuthorizedClient(registrationId, token, request);
		if (client == null) {
			throw new IllegalStateException("인가된 클라이언트가 없어 공급자 토큰을 꺼낼 수 없다");
		}
		return client.getAccessToken().getTokenValue();
	}

	private String errorCodeOf(RuntimeException failure) {
		if (failure instanceof WithdrawalAccountMismatchException) {
			return ClientRedirectCodes.WITHDRAWAL_ACCOUNT_MISMATCH;
		}
		if (failure instanceof UnsupportedSocialProviderException) {
			return ClientRedirectCodes.WITHDRAWAL_UNLINK_FAILED;
		}
		// 해제 호출 실패와 "해제는 됐는데 삭제가 실패"를 같은 코드로 둔다. 사용자가 할 일이
		// 같고(다시 시도), 어느 쪽이었는지는 위 로그가 traceId와 함께 남긴다.
		return ClientRedirectCodes.WITHDRAWAL_FAILED;
	}

	private void issueSession(HttpServletResponse response, OAuth2AuthenticationToken token)
		throws IOException {
		OAuth2User principal = token.getPrincipal();
		OAuthUserInfo userInfo = OAuthUserInfo.from(
			token.getAuthorizedClientRegistrationId(), principal.getAttributes());

		Long memberId = loginTolerantOfFirstLoginRace(userInfo);
		TokenPair tokens = authTokenService.issue(memberId);
		// 리다이렉트는 응답을 커밋하므로 쿠키를 먼저 실어야 한다.
		authCookies.write(response, tokens.accessToken(), tokens.refreshToken());

		// 실패 로그(WARN)와 짝지어 보려면 같은 레벨대에 있어야 한다. 요청마다 남기는 로그가 아니라
		// 세션당 1회이므로 logging.md의 "INFO는 요청마다 남기지 않는다"에 어긋나지 않는다.
		// 이메일·provider_user_id는 남기지 않는다 — 조사에 필요한 것은 memberId와 provider다.
		log.info("social login succeeded: memberId={}, provider={}", memberId, userInfo.provider());

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
