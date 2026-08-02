package com.pinlog.pinlogback.domain.auth.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.global.security.oauth.OAuthEndpointPaths;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 소셜 로그인 진입점(API 명세 3.1).
 *
 * <p>Spring의 인가 엔드포인트를 그대로 노출하지 않고 한 겹 두는 이유는 경로 때문이다.
 * {@code OAuth2AuthorizationRequestRedirectFilter}는 {@code {baseUri}/{registrationId}} 형태로만
 * 매칭해서 registrationId가 반드시 마지막 세그먼트여야 한다. 명세가 정한
 * {@code /auth/{provider}/login}을 그 규칙으로는 만들 수 없어, 여기서 받아 내부 인가 경로로 넘긴다.
 *
 * <p>넘길 경로는 {@link OAuthEndpointPaths}가 갖는다 — 그 경로를 매칭하는 것이 필터 체인이므로
 * 이 컨트롤러는 소비자다.
 *
 * <p>콜백은 사정이 다르다. registrationId를 경로가 아니라 저장된 authorization request에서
 * 꺼내므로 명세 경로({@code /auth/{provider}/callback})를 그대로 쓸 수 있다.
 */
@RestController
@RequestMapping("/v1/auth")
public class SocialLoginController {

	@GetMapping("/{provider}/login")
	public ResponseEntity<Void> login(@PathVariable String provider, HttpServletRequest request) {
		String registrationId = SocialProvider.from(provider)
			.orElseThrow(() -> new UnsupportedSocialProviderException(provider))
			.registrationId();
		URI target = URI.create(
			request.getContextPath() + OAuthEndpointPaths.AUTHORIZATION_BASE_URI + "/" + registrationId);

		return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
	}
}
