package com.pinlog.pinlogback.global.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

import com.pinlog.pinlogback.domain.auth.controller.SocialLoginController;
import com.pinlog.pinlogback.global.security.authentication.JwtAuthenticationFilter;
import com.pinlog.pinlogback.global.security.error.RestAccessDeniedHandler;
import com.pinlog.pinlogback.global.security.error.RestAuthenticationEntryPoint;
import com.pinlog.pinlogback.global.security.oauth.CookieOAuth2AuthorizationRequestRepository;
import com.pinlog.pinlogback.global.security.oauth.OAuthLoginFailureHandler;
import com.pinlog.pinlogback.global.security.oauth.OAuthLoginSuccessHandler;
import com.pinlog.pinlogback.global.security.token.JwtProperties;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;

/**
 * 인증·인가 설정. 경로는 context-path(/api/core)가 제거된 값으로 매칭된다.
 *
 * <p>세션을 만들지 않는다. 인증 상태는 전적으로 쿠키에 담긴 JWT가 들고 있다(11_인증_설계 2).
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	/** 배포 헬스체크·모니터링 경로. 막히면 파드가 뜨지 않는다(authentication.md 3). */
	private static final String[] PUBLIC_ACTUATOR = {
		"/actuator/health",
		"/actuator/health/**",
		"/actuator/prometheus"
	};

	/** 로그인 진입·콜백·재발급·로그아웃. 인증 전에 호출되므로 열려 있어야 한다. */
	private static final String[] PUBLIC_AUTH = {
		"/v1/auth/**"
	};

	/** 운영 프로파일에서는 springdoc 자체가 꺼져 있어 404가 된다(application-prod.yml). */
	private static final String[] PUBLIC_API_DOCS = {
		"/v3/api-docs",
		"/v3/api-docs/**",
		"/swagger-ui.html",
		"/swagger-ui/**"
	};

	/**
	 * 인가 요청에 PKCE를 붙인다. RFC 9700이 Authorization Code 흐름에 요구한다.
	 *
	 * <p>기본 resolver는 client secret이 있는 confidential client에는 PKCE를 넣지 않으므로
	 * 명시적으로 켠다. baseUri는 {@link SocialLoginController}가 넘겨주는 내부 경로다.
	 */
	@Bean
	public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
		ClientRegistrationRepository clientRegistrationRepository
	) {
		DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
			clientRegistrationRepository, SocialLoginController.AUTHORIZATION_BASE_URI);
		resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
		return resolver;
	}

	/**
	 * CSRF 토큰 저장소. {@code CsrfConfigurer.spa()}의 기본 저장소를 그대로 쓰지 않는 이유는
	 * <b>{@code Path}</b> 하나다.
	 *
	 * <p>기본 구현({@code CookieCsrfTokenRepository})은 {@code cookiePath}가 비어 있으면 요청의
	 * context path를 쓴다. 우리는 {@code /api/core}라서 쿠키가 그 경로로 저장되는데, 프론트
	 * 페이지는 {@code /}·{@code /auth/callback} 아래에서 돈다. 그러면 이 쿠키가
	 * {@code document.cookie}에 나타나지 않아 <b>클라이언트가 {@code X-XSRF-TOKEN}에 넣을 값을
	 * 구할 수 없고, 상태 변경 요청이 전부 403이 된다.</b>
	 *
	 * <p>{@code logged_in}에서 같은 판단을 내렸다(BT-04). 읽는 주체가 브라우저 JS인 쿠키는
	 * {@code Path}가 {@code /}여야 한다.
	 *
	 * <p>{@code Secure}·{@code SameSite}도 직접 못박는다. 기본 구현은 {@code secure}를
	 * {@code request.isSecure()}에 맡기는데, 그러면 이 보안 속성이 <b>프록시가 보내는
	 * {@code X-Forwarded-Proto}가 정확한지</b>에 걸린다. {@code AuthCookies}는 같은 이유로 이미
	 * {@code secure(true)}를 고정하고 있다 — 인증 쿠키와 CSRF 쿠키가 다른 규칙을 따를 이유가 없다.
	 *
	 * <p>{@code setSecure} 같은 개별 setter는 없고 {@code setCookieCustomizer}가 유일한 경로다.
	 * 커스터마이저는 기본값을 적용한 <b>뒤에</b> 실행되므로(`saveToken`) 여기서 덮어쓸 수 있다.
	 */
	@Bean
	public CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookiePath("/");
		repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
		return repository;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		RestAuthenticationEntryPoint authenticationEntryPoint,
		RestAccessDeniedHandler accessDeniedHandler,
		OAuth2AuthorizationRequestResolver authorizationRequestResolver,
		CookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
		OAuthLoginSuccessHandler successHandler,
		OAuthLoginFailureHandler failureHandler,
		CookieCsrfTokenRepository csrfTokenRepository,
		JwtTokenProvider jwtTokenProvider
	) throws Exception {
		return http
			.oauth2Login(oauth2 -> oauth2
				.authorizationEndpoint(endpoint -> endpoint
					.authorizationRequestResolver(authorizationRequestResolver)
					// 기본 구현은 HttpSession을 쓴다. STATELESS 선언과 어긋나므로 쿠키로 바꾼다.
					.authorizationRequestRepository(authorizationRequestRepository))
				// 콜백 경로. registrationId를 state에서 꺼내므로 마지막 세그먼트가 아니어도 된다.
				.redirectionEndpoint(endpoint -> endpoint.baseUri("/v1/auth/*/callback"))
				.successHandler(successHandler)
				.failureHandler(failureHandler))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(PUBLIC_ACTUATOR).permitAll()
				.requestMatchers(PUBLIC_AUTH).permitAll()
				.requestMatchers(PUBLIC_API_DOCS).permitAll()
				.anyRequest().authenticated())
			// 쿠키 인증이라 CSRF 방어가 필요하다(08 §1.7). spa()가 요청 핸들러와 저장소를 함께
			// 잡아 주는데, 저장소만 우리 것으로 덮어쓴다 — Path 때문이다(csrfTokenRepository() 참고).
			// spa()의 핸들러는 CsrfConfigurer 내부 클래스라 직접 지정할 수 없어 이 순서가 필요하다.
			.csrf(csrf -> csrf
				.spa()
				.csrfTokenRepository(csrfTokenRepository))
			// spa()의 토큰은 지연 로딩이라 조회 요청에서는 쿠키가 나가지 않는다. 그러면 클라이언트가
			// 첫 상태 변경 요청에 넣을 토큰을 구할 방법이 없다. CsrfFilter 뒤에서 해석을 강제한다.
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			// Access 쿠키를 SecurityContext로 옮긴다. 인가 판정 전에 돌아야 한다.
			.addFilterBefore(
				new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// 기본 401이 로그인 폼 리다이렉트나 WWW-Authenticate로 나가지 않도록 끈다.
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.build();
	}
}
