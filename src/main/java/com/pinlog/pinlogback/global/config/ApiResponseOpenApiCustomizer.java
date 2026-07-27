package com.pinlog.pinlogback.global.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import com.pinlog.pinlogback.global.response.ApiResponse;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * springdoc이 생성하는 OpenAPI 스키마에도 런타임 envelope(ApiResponse)를 반영한다(API 명세 1.6).
 *
 * <p>springdoc은 컨트롤러의 선언된 반환 타입을 introspect하지만
 * {@link com.pinlog.pinlogback.global.web.ApiResponseBodyAdvice}는 런타임에 응답 바디를 감싼다. 두 판정이
 * 어긋나면 문서와 실제 응답이 달라지므로, 이 커스터마이저는 Advice와 <b>동일한 판정</b>(컨트롤러 패키지가
 * {@code com.pinlog.pinlogback.domain} 하위 + 선언된 반환형이 이미 {@link ApiResponse}가 아님)을 재사용한다.
 *
 * <p>대상 operation의 2xx 응답 content 스키마만 {@code success}/{@code data}를 가진 객체 스키마로 감싼다.
 * 원래 스키마가 {@code $ref}면 참조를 그대로 {@code data}에 넣어 스키마 중복 정의를 만들지 않는다. content가
 * 없는 응답(예: 204)은 건드리지 않는다.
 *
 * <p><b>범위 밖:</b> 오류 응답({@code error} 필드) 스키마를 문서화하는 것은 이 커스터마이저가 다루지 않는다.
 */
@Component
public class ApiResponseOpenApiCustomizer implements OperationCustomizer {

	private static final String DOMAIN_PACKAGE = "com.pinlog.pinlogback.domain";

	@Override
	public Operation customize(Operation operation, HandlerMethod handlerMethod) {
		if (!isEnvelopeTarget(handlerMethod)) {
			return operation;
		}
		ApiResponses responses = operation.getResponses();
		if (responses == null) {
			return operation;
		}
		responses.forEach((status, response) -> {
			if (isSuccessStatus(status)) {
				wrapContent(response.getContent());
			}
		});
		return operation;
	}

	private boolean isEnvelopeTarget(HandlerMethod handlerMethod) {
		String packageName = handlerMethod.getBeanType().getPackageName();
		Class<?> returnType = handlerMethod.getReturnType().getParameterType();
		return packageName.startsWith(DOMAIN_PACKAGE) && !ApiResponse.class.isAssignableFrom(returnType);
	}

	private boolean isSuccessStatus(String status) {
		return status != null && status.length() == 3 && status.charAt(0) == '2';
	}

	private void wrapContent(Content content) {
		if (content == null) {
			return;
		}
		content.values().forEach(this::wrapMediaType);
	}

	private void wrapMediaType(MediaType mediaType) {
		Schema<?> original = mediaType.getSchema();
		if (original == null) {
			return;
		}
		mediaType.setSchema(envelopeSchema(original));
	}

	private Schema<?> envelopeSchema(Schema<?> dataSchema) {
		Schema<?> envelope = new Schema<>();
		envelope.type("object");
		envelope.addProperty("success", new Schema<>().type("boolean").example(true));
		envelope.addProperty("data", dataSchema);
		return envelope;
	}
}
