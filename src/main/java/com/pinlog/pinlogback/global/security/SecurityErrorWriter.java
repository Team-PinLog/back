package com.pinlog.pinlogback.global.security;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.global.exception.ErrorCode;
import com.pinlog.pinlogback.global.response.ApiResponse;
import com.pinlog.pinlogback.global.response.ErrorResponse;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Security 필터 체인이 직접 만드는 오류 응답을 공통 envelope로 쓴다.
 *
 * <p>필터 체인은 {@code DispatcherServlet} 이전에 동작하므로
 * {@code @RestControllerAdvice}({@code GlobalExceptionHandler})를 타지 않는다. 그대로 두면
 * 401·403만 본문 없이 나가 응답 계약이 깨지므로 여기서 같은 형태를 직접 만든다.
 */
@Component
public class SecurityErrorWriter {

	private final ObjectMapper objectMapper;

	public SecurityErrorWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(errorCode.getHttpStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");

		ErrorResponse error = ErrorResponse.of(errorCode.getCode(), errorCode.getMessage(), traceId());
		response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(error)));
	}

	private String traceId() {
		return MDC.get(TraceIdFilter.TRACE_ID);
	}
}
