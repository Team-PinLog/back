package com.pinlog.pinlogback.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ApiResponseTest {

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Test
	void okCarriesDataAndNoError() {
		ApiResponse<String> response = ApiResponse.ok("payload");

		assertThat(response.success()).isTrue();
		assertThat(response.data()).isEqualTo("payload");
		assertThat(response.error()).isNull();
	}

	@Test
	void failCarriesErrorAndNoData() {
		ErrorResponse error = ErrorResponse.of("RESOURCE_NOT_FOUND", "not found", "trace-1");

		ApiResponse<Void> response = ApiResponse.fail(error);

		assertThat(response.success()).isFalse();
		assertThat(response.data()).isNull();
		assertThat(response.error()).isEqualTo(error);
	}

	@Test
	void successJsonOmitsErrorKey() throws Exception {
		String json = objectMapper.writeValueAsString(ApiResponse.ok(List.of(1, 2)));

		assertThat(json).contains("\"success\":true").contains("\"data\":[1,2]");
		assertThat(json).doesNotContain("\"error\"");
	}

	@Test
	void failureJsonOmitsDataKey() throws Exception {
		ErrorResponse error = ErrorResponse.of("INVALID_INPUT", "bad", "trace-2");

		String json = objectMapper.writeValueAsString(ApiResponse.fail(error));

		assertThat(json).contains("\"success\":false").contains("\"code\":\"INVALID_INPUT\"");
		assertThat(json).doesNotContain("\"data\"");
	}
}
