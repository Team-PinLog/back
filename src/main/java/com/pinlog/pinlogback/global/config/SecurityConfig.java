package com.pinlog.pinlogback.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.pinlog.pinlogback.global.security.RestAccessDeniedHandler;
import com.pinlog.pinlogback.global.security.RestAuthenticationEntryPoint;

/**
 * 인증·인가 설정. 경로는 context-path(/api/core)가 제거된 값으로 매칭된다.
 *
 * <p>세션을 만들지 않는다. 인증 상태는 전적으로 쿠키에 담긴 JWT가 들고 있다(11_인증_설계 2).
 */
@Configuration
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

	@Bean
	public SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		RestAuthenticationEntryPoint authenticationEntryPoint,
		RestAccessDeniedHandler accessDeniedHandler
	) throws Exception {
		return http
			.authorizeHttpRequests(requests -> requests
				.requestMatchers(PUBLIC_ACTUATOR).permitAll()
				.requestMatchers(PUBLIC_AUTH).permitAll()
				.requestMatchers(PUBLIC_API_DOCS).permitAll()
				.anyRequest().authenticated())
			// 쿠키 인증이라 CSRF 방어가 필요하다. spa()가 XSRF-TOKEN 쿠키(HttpOnly 아님) 발급과
			// X-XSRF-TOKEN 헤더 검증을 함께 설정한다(08 §1.7).
			.csrf(CsrfConfigurer::spa)
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
