package com.pinlog.pinlogback.global.web;

import java.lang.reflect.Method;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;

import com.pinlog.pinlogback.global.response.ApiResponse;

/**
 * 공통 envelope를 적용할 대상인지 판정한다(API 명세 1.6).
 *
 * <p><b>판정을 한 곳에 모아 둔 이유가 이 클래스의 존재 이유다.</b> 런타임에 응답을 감싸는
 * {@link ApiResponseBodyAdvice}와 문서를 생성하는
 * {@code global/config/ApiResponseOpenApiCustomizer}는 <b>같은 결론</b>을 내야 한다. 두 곳이 각자
 * 판정을 들고 있었을 때 {@code ResponseEntity<ApiResponse<T>>}에서 결론이 갈렸고, 그 결과 문서는
 * envelope를 두 번 감싼 형태({@code data.data})를 약속하는데 실제 응답은 한 번만 감싸는 상태가
 * 됐다(S15P11A705-85).
 *
 * <p>판정은 두 가지를 본다.
 *
 * <ul>
 *   <li><b>도메인 컨트롤러인가</b> — URL 패턴이 아니라 패키지로 본다. actuator와 springdoc은 각자
 *       다른 패키지의 핸들러이므로 이 조건에서 자동으로 제외된다. 이들을 감싸면 배포 헬스체크와
 *       Swagger UI가 깨진다.</li>
 *   <li><b>선언된 반환 타입이 이미 envelope인가</b> — {@link ApiResponse}를 직접 반환하는 경우와
 *       {@code ResponseEntity}로 감싼 경우를 모두 잡아야 한다. 후자는 선언 타입이
 *       {@code ResponseEntity}라서 제네릭 인자를 풀어 봐야 알 수 있다.</li>
 * </ul>
 */
public final class EnvelopeTargets {

	private static final String DOMAIN_PACKAGE = "com.pinlog.pinlogback.domain";

	private EnvelopeTargets() {
	}

	/**
	 * 이 핸들러의 응답에 envelope를 적용해야 하는지.
	 *
	 * @param controller 핸들러가 선언된 클래스
	 * @param returnType 핸들러의 반환 타입
	 */
	public static boolean appliesTo(Class<?> controller, MethodParameter returnType) {
		return isDomainController(controller) && !alreadyDeclaresEnvelope(returnType);
	}

	public static boolean isDomainController(Class<?> controller) {
		return controller != null && controller.getPackageName().startsWith(DOMAIN_PACKAGE);
	}

	/**
	 * 선언된 반환 타입이 이미 envelope를 담고 있는지. {@code ResponseEntity<?>}처럼 실제 타입을
	 * 알 수 없는 경우는 {@code false}로 본다 — 그 경우의 최종 방어선은 런타임에 body 자체를 확인하는
	 * {@link ApiResponseBodyAdvice#beforeBodyWrite}다.
	 */
	public static boolean alreadyDeclaresEnvelope(MethodParameter returnType) {
		if (returnType == null) {
			return false;
		}
		if (ApiResponse.class.isAssignableFrom(returnType.getParameterType())) {
			return true;
		}
		if (!ResponseEntity.class.isAssignableFrom(returnType.getParameterType())) {
			return false;
		}
		return ApiResponse.class.isAssignableFrom(declaredBodyType(returnType).toClass());
	}

	private static ResolvableType declaredBodyType(MethodParameter returnType) {
		Method method = returnType.getMethod();
		if (method == null) {
			return ResolvableType.NONE;
		}
		return ResolvableType.forMethodReturnType(method).getGeneric(0);
	}
}
