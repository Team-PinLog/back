package com.pinlog.pinlogback.global.security.error;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.global.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 미인증 요청에 401을 공통 envelope로 응답한다.
 *
 * <p>기본 구현은 로그인 폼으로 리다이렉트하거나 {@code WWW-Authenticate} 헤더를 붙인 빈 401을
 * 내보낸다. 쿠키 기반 API 서버에는 둘 다 맞지 않는다.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final SecurityErrorWriter errorWriter;

	public RestAuthenticationEntryPoint(SecurityErrorWriter errorWriter) {
		this.errorWriter = errorWriter;
	}

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException
	) throws IOException {
		errorWriter.write(response, ErrorCode.UNAUTHORIZED);
	}
}
