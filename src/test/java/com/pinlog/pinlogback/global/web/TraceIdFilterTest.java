package com.pinlog.pinlogback.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

	@Test
	void generatesTraceIdWhenHeaderMissingAndClearsAfter() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		String[] captured = new String[1];
		MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
			@Override
			protected void service(jakarta.servlet.http.HttpServletRequest req,
					jakarta.servlet.http.HttpServletResponse res) {
				captured[0] = MDC.get(TraceIdFilter.TRACE_ID);
			}
		});

		new TraceIdFilter().doFilter(request, response, chain);

		assertThat(captured[0]).isNotBlank();
		assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(captured[0]);
		assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
	}

	@Test
	void reusesIncomingCorrelationHeader() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(TraceIdFilter.HEADER, "incoming-123");
		MockHttpServletResponse response = new MockHttpServletResponse();
		String[] captured = new String[1];
		MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
			@Override
			protected void service(jakarta.servlet.http.HttpServletRequest req,
					jakarta.servlet.http.HttpServletResponse res) {
				captured[0] = MDC.get(TraceIdFilter.TRACE_ID);
			}
		});

		new TraceIdFilter().doFilter(request, response, chain);

		assertThat(captured[0]).isEqualTo("incoming-123");
	}
}
