package com.pinlog.pinlogback.global.security.oauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
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

	private static final String ERROR_CODE = "OAUTH_FAILED";

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

		String target = UriComponentsBuilder.fromUriString(clientRedirectUri)
			.queryParam("error", ERROR_CODE)
			.encode(StandardCharsets.UTF_8)
			.build()
			.toUriString();

		response.sendRedirect(target);
	}
}
