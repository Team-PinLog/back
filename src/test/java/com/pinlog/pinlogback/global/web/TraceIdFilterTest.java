package com.pinlog.pinlogback.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class TraceIdFilterTest {

	/** 운영 로그 패턴({@code logging.pattern.level})이 traceId를 싣는 방식을 그대로 옮긴 것. */
	private static final String PRODUCTION_LOG_PATTERN = "%5p [%X{traceId:-}] %m%n";

	@Test
	void generatesTraceIdWhenHeaderMissingAndClearsAfter() throws Exception {
		Outcome outcome = runFilterWith(null);

		assertThat(outcome.traceId()).isNotBlank();
		assertThat(outcome.response().getHeader(TraceIdFilter.HEADER)).isEqualTo(outcome.traceId());
		assertThat(MDC.get(TraceIdFilter.TRACE_ID)).isNull();
	}

	@Test
	void reusesIncomingCorrelationHeader() throws Exception {
		Outcome outcome = runFilterWith("incoming-123");

		assertThat(outcome.traceId()).isEqualTo("incoming-123");
		assertThat(outcome.response().getHeader(TraceIdFilter.HEADER)).isEqualTo("incoming-123");
	}

	/**
	 * 개행이 섞인 헤더로 로그 한 줄을 두 줄로 만들 수 있는지 — 로그 위조의 핵심 시나리오다.
	 * 값이 거절되는지만 보지 않고, 실제 운영 패턴으로 렌더링해 <b>줄 수가 그대로 하나</b>인지까지 확인한다.
	 */
	@Test
	void newlineInHeaderCannotForgeAnExtraLogLine() throws Exception {
		String forged = "abc" + System.lineSeparator() + "ERROR [] forged log line";

		Outcome outcome = runFilterWith(forged);

		assertThat(outcome.traceId())
			.as("개행이 섞인 값은 채택하지 않는다")
			.isNotEqualTo(forged);
		assertThat(outcome.response().getHeader(TraceIdFilter.HEADER))
			.as("응답 헤더에도 검증된 값만 실린다")
			.isEqualTo(outcome.traceId());
		assertThat(render(outcome.loggedEvents().getFirst()).lines())
			.as("운영 패턴으로 렌더링했을 때 로그가 한 줄로 유지되는지")
			.hasSize(1);
	}

	@Test
	void headerLongerThanLimitIsReplaced() throws Exception {
		String tooLong = "a".repeat(TraceIdFilter.MAX_LENGTH + 1);

		Outcome outcome = runFilterWith(tooLong);

		assertThat(outcome.traceId())
			.isNotEqualTo(tooLong)
			.hasSizeLessThanOrEqualTo(TraceIdFilter.MAX_LENGTH);
	}

	@Test
	void headerExactlyAtLimitIsAccepted() throws Exception {
		String atLimit = "a".repeat(TraceIdFilter.MAX_LENGTH);

		Outcome outcome = runFilterWith(atLimit);

		assertThat(outcome.traceId()).isEqualTo(atLimit);
	}

	@ParameterizedTest(name = "거절: [{0}]")
	@ValueSource(strings = {
		"",
		" ",
		"has space",
		"tab\there",
		"under_score",
		"semi;colon",
		"path/traversal",
		"percent%0Aencoded",
		"bracket[0]"
	})
	void unsafeHeaderValuesAreReplaced(String unsafe) throws Exception {
		Outcome outcome = runFilterWith(unsafe);

		assertThat(outcome.traceId()).isNotEqualTo(unsafe).isNotBlank();
		assertThat(render(outcome.loggedEvents().getFirst()).lines()).hasSize(1);
	}

	/**
	 * 필터를 한 번 통과시키고, 체인 안에서 관측한 MDC 값과 그때 남은 로그 이벤트를 함께 돌려준다.
	 * 로그 이벤트를 같이 받는 이유는 MDC에 담긴 값이 실제 로그 한 줄에 어떻게 렌더링되는지까지
	 * 확인해야 로그 위조 방지를 단정할 수 있기 때문이다.
	 */
	private Outcome runFilterWith(String incomingHeader) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (incomingHeader != null) {
			request.addHeader(TraceIdFilter.HEADER, incomingHeader);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();

		Logger logger = (Logger)LoggerFactory.getLogger(TraceIdFilterTest.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		String[] observed = new String[1];
		try {
			new TraceIdFilter().doFilter(request, response, new MockFilterChain(new HttpServlet() {
				@Override
				protected void service(HttpServletRequest req, HttpServletResponse res) {
					observed[0] = MDC.get(TraceIdFilter.TRACE_ID);
					logger.info("정상 로그 한 줄");
				}
			}));
		} finally {
			logger.detachAppender(appender);
		}

		return new Outcome(observed[0], response, List.copyOf(appender.list));
	}

	private String render(ILoggingEvent event) {
		PatternLayout layout = new PatternLayout();
		layout.setContext((LoggerContext)LoggerFactory.getILoggerFactory());
		layout.setPattern(PRODUCTION_LOG_PATTERN);
		layout.start();
		return layout.doLayout(event);
	}

	private record Outcome(String traceId, MockHttpServletResponse response, List<ILoggingEvent> loggedEvents) {
	}
}
