package com.pinlog.pinlogback.global.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.pinlog.pinlogback.global.security.authentication.LoginMemberArgumentResolver;

/** 컨트롤러가 {@code @LoginMember}로 인증 주체를 받도록 리졸버를 등록한다. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new LoginMemberArgumentResolver());
	}
}
