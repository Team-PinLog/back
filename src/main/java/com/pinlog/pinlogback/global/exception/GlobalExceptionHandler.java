package com.pinlog.pinlogback.global.exception;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.pinlog.pinlogback.global.response.ApiResponse;
import com.pinlog.pinlogback.global.response.ErrorResponse;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
		log.warn("business error: code={}, message={}", ex.getCode(), ex.getMessage());
		ErrorResponse error = ErrorResponse.of(ex.getCode(), ex.getMessage(), traceId());
		return ResponseEntity.status(ex.getHttpStatus()).body(ApiResponse.fail(error));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
			.map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		log.warn("validation error: {}", fieldErrors);
		ErrorResponse error = ErrorResponse.of(
			ErrorCode.INVALID_INPUT.getCode(),
			ErrorCode.INVALID_INPUT.getMessage(),
			fieldErrors,
			traceId());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getHttpStatus()).body(ApiResponse.fail(error));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
		ErrorResponse error = ErrorResponse.of(
			ErrorCode.RESOURCE_NOT_FOUND.getCode(),
			ErrorCode.RESOURCE_NOT_FOUND.getMessage(),
			traceId());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(error));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
		log.error("unhandled error", ex);
		ErrorResponse error = ErrorResponse.of(
			ErrorCode.INTERNAL_ERROR.getCode(),
			ErrorCode.INTERNAL_ERROR.getMessage(),
			traceId());
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus()).body(ApiResponse.fail(error));
	}

	private String traceId() {
		return MDC.get(TraceIdFilter.TRACE_ID);
	}
}
