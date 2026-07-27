package com.pinlog.pinlogback.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.pinlog.pinlogback.global.web.TraceIdFilter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ErrorTestController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(new TraceIdFilter())
			.build();
	}

	@Test
	void businessExceptionMapsToContract() throws Exception {
		mockMvc.perform(get("/test/business"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.error.traceId", not(blankOrNullString())))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(header().string(TraceIdFilter.HEADER, not(blankOrNullString())));
	}

	@Test
	void validationFailureReturns400WithFieldErrors() throws Exception {
		mockMvc.perform(post("/test/validate")
				.contentType("application/json")
				.content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"))
			.andExpect(jsonPath("$.error.traceId", not(blankOrNullString())))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void unhandledExceptionReturns500WithoutLeakingInternals() throws Exception {
		mockMvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.error.message").value(not("boom")))
			.andExpect(jsonPath("$.error.traceId", not(blankOrNullString())))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		mockMvc.perform(post("/test/validate")
				.contentType("application/json")
				.content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.error.traceId", not(blankOrNullString())));
	}

	@Test
	void unsupportedMethodReturns405() throws Exception {
		mockMvc.perform(delete("/test/business"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void unsupportedMediaTypeReturns415() throws Exception {
		mockMvc.perform(post("/test/validate")
				.contentType("text/plain")
				.content("hello"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void missingRequiredParameterReturns400() throws Exception {
		mockMvc.perform(get("/test/param"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	/**
	 * 미매핑 URL의 404 계약을 지키는 회귀 테스트. 이 예외는 이제 부모의
	 * {@code handleNoResourceFoundException}을 타므로, 상태 코드 매핑이 깨지면 여기서 먼저 드러난다.
	 * 실제 미매핑 URL로 재현하려면 정적 리소스 핸들러가 필요하므로 예외를 직접 던진다
	 * (전체 경로 검증은 {@code DeploymentContractTests}).
	 */
	@Test
	void noResourceFoundStillReturns404() throws Exception {
		mockMvc.perform(get("/test/no-resource"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.error.traceId", not(blankOrNullString())));
	}

	/**
	 * 부모가 500으로 넘기는 프레임워크 예외(`handleHttpMessageNotWritable`)도 envelope + INTERNAL_ERROR로
	 * 응답하고, 5xx이므로 ERROR로 로깅되는지 지킨다.
	 *
	 * <p>응답 본문만으로는 이 경로와 catch-all {@code handleUnexpected}를 구분할 수 없다(둘 다 500 +
	 * INTERNAL_ERROR). 그래서 로그 이벤트로 경유 분기를 단정한다: {@code "framework error: status=500"}은
	 * {@code handleExceptionInternal}에만 있는 문구이고 catch-all은 {@code "unhandled error"}를 남기므로,
	 * 이벤트가 정확히 하나이고 그 문구라는 것이 곧 "부모 분기를 탔고 catch-all은 관여하지 않았다"는 뜻이다.
	 */
	@Test
	void frameworkServerErrorReturns500AndLogsAtErrorLevel() throws Exception {
		Logger handlerLogger = (Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		handlerLogger.addAppender(appender);
		try {
			mockMvc.perform(get("/test/unwritable"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value(not("unwritable boom")))
				.andExpect(jsonPath("$.error.traceId", not(blankOrNullString())))
				.andExpect(jsonPath("$.data").doesNotExist());
		} finally {
			handlerLogger.detachAppender(appender);
		}

		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.ERROR);
			assertThat(event.getFormattedMessage()).isEqualTo("framework error: status=500");
		});
	}

	@RestController
	static class ErrorTestController {

		@GetMapping("/test/business")
		void business() {
			throw new SampleNotFoundException();
		}

		@PostMapping("/test/validate")
		void validate(@Valid @RequestBody SampleRequest request) {
			// no-op; validation runs before body
		}

		@GetMapping("/test/param")
		void param(@RequestParam String required) {
			// no-op; the missing parameter fails during binding
		}

		@GetMapping("/test/no-resource")
		void noResource() throws NoResourceFoundException {
			throw new NoResourceFoundException(HttpMethod.GET, "/test/no-resource", "no-resource");
		}

		@GetMapping("/test/unwritable")
		void unwritable() {
			throw new HttpMessageNotWritableException("unwritable boom");
		}

		@GetMapping("/test/boom")
		void boom() {
			throw new RuntimeException("boom");
		}
	}

	static final class SampleNotFoundException extends BusinessException {
		private SampleNotFoundException() {
			super(ErrorCode.RESOURCE_NOT_FOUND);
		}
	}

	record SampleRequest(@NotBlank String name) {
	}
}
