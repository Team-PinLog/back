package com.pinlog.pinlogback.global.response;

import java.util.List;

public record ErrorResponse(
	String code,
	String message,
	List<FieldError> fieldErrors,
	String traceId
) {

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(String code, String message, String traceId) {
		return new ErrorResponse(code, message, List.of(), traceId);
	}

	public static ErrorResponse of(String code, String message, List<FieldError> fieldErrors, String traceId) {
		return new ErrorResponse(code, message, fieldErrors, traceId);
	}
}
