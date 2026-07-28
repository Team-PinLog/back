package com.pinlog.pinlogback.global.security;

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
		log.warn("social login failed: {}", exception.getMessage());

		String target = UriComponentsBuilder.fromUriString(clientRedirectUri)
			.queryParam("error", ERROR_CODE)
			.encode(StandardCharsets.UTF_8)
			.build()
			.toUriString();

		response.sendRedirect(target);
	}
}
