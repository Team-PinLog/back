package com.pinlog.pinlogback.domain.auth.controller;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.global.security.oauth.ClientRedirectCodes;
import com.pinlog.pinlogback.global.security.oauth.OAuthEndpointPaths;
import com.pinlog.pinlogback.global.security.oauth.WithdrawalAwareAuthorizationRequestResolver;

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

	private final String clientRedirectUri;

	public SocialLoginController(
		@Value("${pinlog.auth.client-redirect-uri}") String clientRedirectUri) {
		this.clientRedirectUri = clientRedirectUri;
	}

	/**
	 * 인가 진입이 요청을 만들지 못했을 때만 닿는다.
	 *
	 * <p>{@code OAuth2AuthorizationRequestRedirectFilter}가 처리한 요청은 여기 오지 않는다 — 필터가
	 * 리다이렉트로 응답을 끝내기 때문이다. 즉 이 메서드는 <b>resolver가 {@code null}을 돌려준
	 * 경우</b>만 받는다.
	 *
	 * <p>탈퇴 티켓이 실려 있었다면 위조이거나 만료다. 그대로 두면 404라 사용자는 이유를 모르므로,
	 * 복귀 경로에 코드를 실어 돌려보낸다(BD-48 §④). 티켓이 없었다면 지원하지 않는 registrationId로
	 * 내부 경로를 직접 두드린 것이라 404가 맞다.
	 *
	 * <p>{@code {registrationId}}를 {@code @PathVariable}로 받지 않는다. 필터가 매칭하는 경로 형태를
	 * 그대로 덮어야 여기까지 흘러오므로 <b>템플릿에는 필요하지만</b>, 이 메서드가 하는 일(티켓 유무로
	 * 갈라 돌려보내기)은 공급자와 무관하다. 값을 받아 두면 쓰지 않는 파라미터가 는다.
	 */
	@GetMapping("/authorize/{registrationId}")
	public ResponseEntity<Void> authorizationRequestNotResolved(
		@RequestParam(name = WithdrawalAwareAuthorizationRequestResolver.TICKET_PARAMETER,
			required = false) @Nullable String ticket
	) {
		if (ticket == null) {
			throw new UnsupportedSocialProviderException("authorize");
		}
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(URI.create(UriComponentsBuilder.fromUriString(clientRedirectUri)
				.queryParam("error", ClientRedirectCodes.WITHDRAWAL_FAILED)
				.encode(StandardCharsets.UTF_8)
				.build()
				.toUriString()))
			.build();
	}

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
