package com.pinlog.pinlogback.global.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.pinlog.pinlogback.global.security.LoginMemberArgumentResolver;

/**
 * MVC 확장 등록. 인증 스텁 리졸버는 프로퍼티가 꺼져 있어도 등록한다 — 등록하지 않으면
 * {@code @LoginMember} 파라미터가 바인딩 실패(500)로 빠지는데, 계약은 401(fail-closed)이다.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final boolean stubEnabled;

	public WebMvcConfig(@Value("${pinlog.auth.stub.enabled:false}") boolean stubEnabled) {
		this.stubEnabled = stubEnabled;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new LoginMemberArgumentResolver(stubEnabled));
	}
}
