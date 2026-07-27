package com.pinlog.pinlogback.global.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pinlog.pinlogback.domain.sample.EnvelopeTestController;

class ApiResponseBodyAdviceTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new EnvelopeTestController())
			.setControllerAdvice(new ApiResponseBodyAdvice())
			.build();
	}

	@Test
	void dtoIsWrappedInEnvelope() throws Exception {
		mockMvc.perform(get("/test-envelope/dto"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("pinlog"))
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void alreadyWrappedResponseIsNotDoubleWrapped() throws Exception {
		mockMvc.perform(get("/test-envelope/already-wrapped"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("pinlog"))
			.andExpect(jsonPath("$.data.data").doesNotExist());
	}

	@Test
	void voidResponseHasEmptyBody() throws Exception {
		mockMvc.perform(get("/test-envelope/void"))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
	}
}
