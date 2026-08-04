package com.pinlog.pinlogback.global.security.oauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 공급자 인증 실패를 클라이언트 복귀 경로로 돌려보낸다(API 명세 3.2).
 *
 * <p>실패 사유를 그대로 노출하지 않는다. 클라이언트는 고정된 code 하나만 보고 재로그인을 유도하고,
 * 원인은 traceId로 로그에서 찾는다.
 */
@Slf4j
@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

	/** RFC 6749 §4.1.2.1이 규정한 값. 사용자가 공급자 화면에서 거절했을 때 온다. */
	private static final String ACCESS_DENIED = "access_denied";

	private final String clientRedirectUri;

	public OAuthLoginFailureHandler(@Value("${pinlog.auth.client-redirect-uri}") String clientRedirectUri) {
		this.clientRedirectUri = clientRedirectUri;
	}

	@Override
	public void onAuthenticationFailure(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException exception
	) throws IOException {
		// 타입까지 남기는 이유: 메시지만 남기면 Spring이 던진 OAuth 오류
		// (authorization_request_not_found·invalid_grant)와 성공 핸들러가 감싸 넘긴 내부 실패
		// (Redis 순단·중복키)가 한 줄로 뭉개져 사후 구별이 안 된다. 실제로 그 때문에 운영 조사가
		// 한 번 막혔다(S15P11A705-186).
		//
		// 예외 객체를 함께 넘겨 스택과 cause 체인이 남게 한다(logging.md). 문자열로 이어붙이면
		// cause가 사라진다.
		log.warn("social login failed: [{}] {}",
			exception.getClass().getSimpleName(), exception.getMessage(), exception);

		response.sendRedirect(redirectWith(errorCodeFor(request, exception)));
	}

	/**
	 * 클라이언트 복귀 경로에 코드를 실어 돌려보낸다. 성공 처리도 탈퇴 실패를 이리로 넘기므로
	 * 코드를 받는 형태로 열어 둔다.
	 */
	String redirectWith(String errorCode) {
		return UriComponentsBuilder.fromUriString(clientRedirectUri)
			.queryParam("error", errorCode)
			.encode(StandardCharsets.UTF_8)
			.build()
			.toUriString();
	}

	/**
	 * 이 왕복이 탈퇴였는지는 소비된 인가 요청만 안다(BD-48 §③). 실패 경로에서도 그 정보가 필요한
	 * 이유는 어휘가 갈리기 때문이다 — 사용자가 공급자 화면에서 취소했을 때 프론트가 보여야 하는
	 * 문구가 로그인과 탈퇴에서 다르다.
	 *
	 * <p>탈퇴 왕복이라고 전부 "취소"는 아니다. 토큰 교환 실패나 공급자 장애도 이리로 오는데 그것을
	 * 취소라고 하면 사실이 아니다 — {@code access_denied}일 때만 취소로 부른다.
	 */
	private String errorCodeFor(HttpServletRequest request, AuthenticationException exception) {
		if (WithdrawalAwareAuthorizationRequestResolver.withdrawalMemberId(request).isEmpty()) {
			return ClientRedirectCodes.OAUTH_FAILED;
		}
		return isAccessDenied(exception)
			? ClientRedirectCodes.WITHDRAWAL_CANCELLED
			: ClientRedirectCodes.WITHDRAWAL_FAILED;
	}

	private boolean isAccessDenied(AuthenticationException exception) {
		return exception instanceof OAuth2AuthenticationException oauth2
			&& ACCESS_DENIED.equals(oauth2.getError().getErrorCode());
	}
}
