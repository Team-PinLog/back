package com.pinlog.pinlogback.global.web;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String TRACE_ID = "traceId";
	public static final String HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String traceId = request.getHeader(HEADER);
		if (traceId == null || traceId.isBlank()) {
			traceId = UUID.randomUUID().toString();
		}
		MDC.put(TRACE_ID, traceId);
		response.setHeader(HEADER, traceId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(TRACE_ID);
		}
	}
}
