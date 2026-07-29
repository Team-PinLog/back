package com.pinlog.pinlogback.global.security.error;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.global.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 접근 거부에 403을 공통 envelope로 응답한다.
 *
 * <p>현재 403이 나오는 경로는 CSRF 토큰 누락·불일치뿐이다. 타인 소유 자원 접근은 존재를 숨기기 위해
 * 403이 아니라 404로 응답하며, 그 판정은 도메인 서비스가 한다(08 §1.2).
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final SecurityErrorWriter errorWriter;

	public RestAccessDeniedHandler(SecurityErrorWriter errorWriter) {
		this.errorWriter = errorWriter;
	}

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException accessDeniedException
	) throws IOException {
		errorWriter.write(response, ErrorCode.FORBIDDEN);
	}
}
