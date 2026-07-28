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
 * 도메인 컨트롤러가 반환한 DTO를 공통 envelope(ApiResponse)로 감싼다(API 명세 1.6).
 *
 * <p>"어떤 핸들러를 감쌀 것인가"의 판정은 {@link EnvelopeTargets}가 갖는다 — 문서를 생성하는
 * {@code ApiResponseOpenApiCustomizer}와 같은 결론을 내야 하기 때문이다. 여기서는 그 판정에
 * <b>컨버터 조건만 추가</b>한다: String 응답이 StringHttpMessageConverter로 처리될 때는 envelope를
 * 문자열로 쓸 수 없으므로 대상에서 빼야 한다.
 *
 * <p>{@link #beforeBodyWrite}의 {@code instanceof ApiResponse} 검사는 남겨 둔다. 선언 타입으로는
 * 알 수 없는 경우({@code ResponseEntity<?>} 등)의 최종 방어선이고, 이중 래핑은 여기서 확실히 막힌다.
 *
 * <p>Jackson 3(tools.jackson) 기준 컨버터 공통 상위 타입은 Jackson 2 시절의
 * {@code AbstractJackson2HttpMessageConverter}(org.springframework.http.converter.json)가 아니라
 * {@link AbstractJacksonHttpMessageConverter}(org.springframework.http.converter)다. Spring
 * Framework 7.0.8 기준 {@code JacksonJsonHttpMessageConverter}가 이 타입을 직접 상속한다.
 */
@RestControllerAdvice
public class ApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return EnvelopeTargets.appliesTo(returnType.getContainingClass(), returnType)
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
}
