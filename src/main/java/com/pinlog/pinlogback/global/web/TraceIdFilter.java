package com.pinlog.pinlogback.global.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 traceId를 확보해 MDC와 응답 헤더에 싣는다(로깅 규약).
 *
 * <p>클라이언트가 보낸 {@link #HEADER}를 <b>그대로 쓰지 않고 검증한다.</b> 이 값은 로그 패턴의
 * {@code %X{traceId}}와 오류 응답의 {@code traceId}로 흘러가므로, 검증하지 않으면 두 가지가 열린다.
 *
 * <ul>
 *   <li>개행을 섞어 <b>로그에 없던 줄을 만들어 낸다</b> — 있지도 않은 ERROR 라인을 심을 수 있다.</li>
 *   <li>길이 상한이 없어 한 요청이 로그를 임의로 부풀린다.</li>
 * </ul>
 *
 * <p>로깅 규약이 "로그 메시지에 사용자 입력을 그대로 넣을 때는 개행 주입에 주의한다"고 정한 지점이
 * 정확히 여기다. 그래서 허용 목록 방식으로 형식과 길이를 검사하고, 통과하지 못한 값은 <b>거절하고
 * 서버가 만든 값으로 대체한다.</b>
 *
 * <p>거절할 때 요청을 400으로 실패시키지 않는 이유: 상관관계 ID는 부가 정보이고, 그 값이 이상하다고
 * 정상 요청을 막을 이유가 없다. 거절한 값을 로그로 남기지도 않는다 — 남기는 순간 막으려던 주입 경로가
 * 다시 열린다.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String TRACE_ID = "traceId";
	public static final String HEADER = "X-Request-Id";

	/**
	 * 받아들이는 최대 길이. UUID(36자)와 흔히 쓰이는 상관관계 ID가 모두 들어가고,
	 * 로그 한 줄을 부풀리지는 못하는 크기로 잡았다.
	 */
	public static final int MAX_LENGTH = 64;

	/**
	 * 영숫자와 하이픈만 허용한다. 개행·탭 같은 제어문자, 공백, 구분자가 모두 여기서 걸린다.
	 * {@code matches()}로 <b>전체</b> 일치를 요구하므로 일부만 맞는 값은 통과하지 못한다.
	 */
	private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9-]{1," + MAX_LENGTH + "}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String traceId = acceptOrGenerate(request.getHeader(HEADER));
		MDC.put(TRACE_ID, traceId);
		response.setHeader(HEADER, traceId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(TRACE_ID);
		}
	}

	private String acceptOrGenerate(String incoming) {
		if (incoming == null || !ALLOWED.matcher(incoming).matches()) {
			return UUID.randomUUID().toString();
		}
		return incoming;
	}
}
