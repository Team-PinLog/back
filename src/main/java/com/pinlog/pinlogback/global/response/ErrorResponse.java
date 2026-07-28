package com.pinlog.pinlogback.global.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 오류 형식(API 명세 1.5). 일부 code는 추가 필드를 더한다 —
 * {@code DELETE_CONFIRMATION_REQUIRED}가 연쇄 삭제 영향을 {@link Impact}로 반환하는 것이 그 예다.
 * impact는 해당 code에만 존재하므로 null이면 직렬화에서 생략한다.
 */
public record ErrorResponse(
	String code,
	String message,
	List<FieldError> fieldErrors,
	String traceId,
	@JsonInclude(JsonInclude.Include.NON_NULL) Impact impact
) {

	public record FieldError(String field, String message) {
	}

	/**
	 * 연쇄 삭제 영향 범위(API 명세 5.6·5.7). 프론트가 "기록/컬렉션도 함께 사라집니다" 안내에 쓴다.
	 */
	public record Impact(boolean recordDeleted, List<Long> collectionIds) {
	}

	public static ErrorResponse of(String code, String message, String traceId) {
		return new ErrorResponse(code, message, List.of(), traceId, null);
	}

	public static ErrorResponse of(String code, String message, List<FieldError> fieldErrors, String traceId) {
		return new ErrorResponse(code, message, fieldErrors, traceId, null);
	}

	public static ErrorResponse of(String code, String message, String traceId, Impact impact) {
		return new ErrorResponse(code, message, List.of(), traceId, impact);
	}
}
