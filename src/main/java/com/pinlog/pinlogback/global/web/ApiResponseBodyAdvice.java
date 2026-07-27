package com.pinlog.pinlogback.global.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.pinlog.pinlogback.global.response.ApiResponse;

/**
 * 도메인 컨트롤러가 반환한 DTO를 공통 봉투(ApiResponse)로 감싼다(API 명세 1.6).
 *
 * <p>판정을 URL 패턴이 아니라 <b>컨트롤러 패키지</b>로 하는 이유: actuator와 springdoc은 각자
 * 다른 패키지의 핸들러이므로 이 조건에서 자동으로 제외된다. 이들을 감싸면 배포 헬스체크와
 * Swagger UI가 깨진다. Jackson 컨버터 조건은 String 응답이 StringHttpMessageConverter로
 * 처리될 때 봉투를 문자열로 쓸 수 없는 문제를 막는다.
 *
 * <p>Jackson 3(tools.jackson) 기준 컨버터 공통 상위 타입은 Jackson 2 시절의
 * {@code AbstractJackson2HttpMessageConverter}(org.springframework.http.converter.json)가 아니라
 * {@link AbstractJacksonHttpMessageConverter}(org.springframework.http.converter)다. Spring
 * Framework 7.0.8 기준 {@code JacksonJsonHttpMessageConverter}가 이 타입을 직접 상속한다.
 */
@RestControllerAdvice
public class ApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

	private static final String DOMAIN_PACKAGE = "com.pinlog.pinlogback.domain";

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return isDomainController(returnType)
			&& !ApiResponse.class.isAssignableFrom(returnType.getParameterType())
			&& AbstractJacksonHttpMessageConverter.class.isAssignableFrom(converterType);
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
		Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
		ServerHttpResponse response) {
		if (body == null) {
			return null;
		}
		if (body instanceof ApiResponse<?>) {
			return body;
		}
		return ApiResponse.ok(body);
	}

	private boolean isDomainController(MethodParameter returnType) {
		Class<?> controller = returnType.getContainingClass();
		return controller != null && controller.getPackageName().startsWith(DOMAIN_PACKAGE);
	}
}
