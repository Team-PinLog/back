package com.pinlog.pinlogback.global.exception;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.pinlog.pinlogback.global.response.ApiResponse;
import com.pinlog.pinlogback.global.response.ErrorResponse;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * 모든 예외를 공통 envelope(ApiResponse.fail)로 변환한다(API 명세 1.6).
 *
 * <p>{@link ResponseEntityExceptionHandler}를 상속하는 이유: 상속하지 않으면 malformed JSON,
 * 405, 415, 필수 파라미터 누락 같은 프레임워크 예외가 모두 catch-all {@code Exception} 분기에 잡혀
 * 500으로 응답한다. 부모가 이 예외들을 각자의 상태 코드로 매핑해 주고, 우리는
 * {@link #handleExceptionInternal}에서 body만 envelope로 교체한다.
 *
 * <p>부모의 {@code handleException}은 {@link MethodArgumentNotValidException}과
 * {@code NoResourceFoundException}을 포함한 20개 예외를 이미 {@code @ExceptionHandler}로 선언한다.
 * 같은 예외 타입을 자식에서 {@code @ExceptionHandler}로 다시 선언하면
 * {@code ExceptionHandlerMethodResolver}가 "Ambiguous @ExceptionHandler method mapped for ..."
 * IllegalStateException으로 advice 자체를 죽인다. 따라서 검증 예외는 부모 시그니처
 * {@link #handleMethodArgumentNotValid} 오버라이드로 옮겼고, 404는 상태 코드 매핑이 담당한다.
 *
 * <p>부모는 body를 {@link org.springframework.http.ProblemDetail}로 만든다. 이를 그대로 내보내면
 * 응답 계약이 깨지므로 {@link #handleExceptionInternal}에서 반드시 envelope로 교체한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	/**
	 * DELETE_CONFIRMATION_REQUIRED는 error에 impact(연쇄 삭제 영향)를 추가로 싣는다(API 명세 1.5).
	 * BusinessException보다 구체적인 타입이므로 이 핸들러가 우선한다.
	 */
	@ExceptionHandler(DeleteConfirmationRequiredException.class)
	public ResponseEntity<ApiResponse<Void>> handleDeleteConfirmation(DeleteConfirmationRequiredException ex) {
		log.warn("delete confirmation required: impact={}", ex.getImpact());
		ErrorResponse error = ErrorResponse.of(ex.getCode(), ex.getMessage(), traceId(), ex.getImpact());
		return ResponseEntity.status(ex.getHttpStatus()).body(ApiResponse.fail(error));
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
		log.warn("business error: code={}, message={}", ex.getCode(), ex.getMessage());
		ErrorResponse error = ErrorResponse.of(ex.getCode(), ex.getMessage(), traceId());
		return ResponseEntity.status(ex.getHttpStatus()).body(ApiResponse.fail(error));
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

	/**
	 * Bean Validation 실패는 어떤 필드가 왜 틀렸는지까지 알려야 하므로 fieldErrors를 채운 envelope를
	 * 직접 만들어 넘긴다. 나머지 프레임워크 예외는 body가 null로 들어와 상태 코드만으로 매핑된다.
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
		HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
			.map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();
		log.warn("validation error: {}", fieldErrors);
		ErrorResponse error = ErrorResponse.of(
			ErrorCode.INVALID_INPUT.getCode(),
			ErrorCode.INVALID_INPUT.getMessage(),
			fieldErrors,
			traceId());
		return handleExceptionInternal(ex, ApiResponse.fail(error), headers, status, request);
	}

	/**
	 * 부모가 만든 body(ProblemDetail)를 버리고 envelope로 교체한다. 이미 envelope가 넘어온
	 * 경우(우리 오버라이드가 만든 fieldErrors 응답)에는 그대로 통과시킨다.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
		HttpStatusCode statusCode, WebRequest request) {
		if (statusCode.is5xxServerError()) {
			log.error("framework error: status={}", statusCode.value(), ex);
		} else {
			log.warn("framework error: status={}, type={}", statusCode.value(), ex.getClass().getSimpleName());
		}
		Object envelope = body instanceof ApiResponse<?> ? body : ApiResponse.fail(errorOf(ex, statusCode));
		return super.handleExceptionInternal(ex, envelope, headers, statusCode, request);
	}

	private ErrorResponse errorOf(Exception ex, HttpStatusCode statusCode) {
		ErrorCode errorCode = ex instanceof MaxUploadSizeExceededException
			? ErrorCode.IMAGE_TOO_LARGE : errorCodeOf(statusCode);
		return ErrorResponse.of(errorCode.getCode(), errorCode.getMessage(), traceId());
	}

	private ErrorCode errorCodeOf(HttpStatusCode statusCode) {
		if (statusCode.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
			return ErrorCode.METHOD_NOT_ALLOWED;
		}
		if (statusCode.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
			return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
		}
		if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
			return ErrorCode.RESOURCE_NOT_FOUND;
		}
		return statusCode.is4xxClientError() ? ErrorCode.INVALID_INPUT : ErrorCode.INTERNAL_ERROR;
	}

	private String traceId() {
		return MDC.get(TraceIdFilter.TRACE_ID);
	}
}
