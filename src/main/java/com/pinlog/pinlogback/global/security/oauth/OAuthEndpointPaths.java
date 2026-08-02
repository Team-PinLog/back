package com.pinlog.pinlogback.global.security.oauth;

/**
 * OAuth2 필터 체인이 매칭하는 내부 경로.
 *
 * <p><b>소유자가 필터 체인이라 여기에 둔다.</b> 이 값의 의미를 확정하는 것은 {@code SecurityConfig}가
 * 조립하는 필터이고, {@code SocialLoginController}는 그 경로로 넘겨주는 소비자다. 반대로 두면
 * 설정이 도메인 컨트롤러를 참조하게 되어 의존 방향이 뒤집힌다.
 *
 * <p>{@link #AUTHORIZATION_BASE_URI}에 한 겹이 필요한 이유는 매칭 규칙이다.
 * {@code OAuth2AuthorizationRequestRedirectFilter}는 {@code {baseUri}/{registrationId}} 형태로만
 * 매칭해서 registrationId가 반드시 마지막 세그먼트여야 하는데, 명세가 정한
 * {@code /auth/{provider}/login}은 그 형태가 아니다. 컨트롤러가 받아 이 경로로 넘긴다.
 *
 * <p>{@link #CALLBACK_BASE_URI}는 공급자에게 보내는 절대 URL과 짝이고, 그쪽 정본은
 * {@code application.yml}의 {@code spring.security.oauth2.client.registration.*.redirect-uri}다.
 * 형식이 달라 한 상수로 합칠 수 없으므로 두 값이 같은 경로를 가리키는지는 사람이 지킨다 —
 * 어긋나면 공급자가 {@code redirect_uri_mismatch}로 거절한다.
 */
public final class OAuthEndpointPaths {

	/** 인가 요청 진입. registrationId가 마지막 세그먼트로 붙는다. */
	public static final String AUTHORIZATION_BASE_URI = "/v1/auth/authorize";

	/** 공급자 콜백. registrationId를 state에서 꺼내므로 마지막 세그먼트가 아니어도 된다. */
	public static final String CALLBACK_BASE_URI = "/v1/auth/*/callback";

	private OAuthEndpointPaths() {
	}
}
