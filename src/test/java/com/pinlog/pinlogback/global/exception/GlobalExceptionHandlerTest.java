package com.pinlog.pinlogback.global.exception;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.global.web.TraceIdFilter;

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
