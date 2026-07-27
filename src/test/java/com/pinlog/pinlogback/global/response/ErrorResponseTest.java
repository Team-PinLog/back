package com.pinlog.pinlogback.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ErrorResponseTest {

	@Test
	void ofWithoutFieldErrorsUsesEmptyList() {
		ErrorResponse response = ErrorResponse.of("RESOURCE_NOT_FOUND", "not found", "trace-1");

		assertThat(response.code()).isEqualTo("RESOURCE_NOT_FOUND");
		assertThat(response.message()).isEqualTo("not found");
		assertThat(response.traceId()).isEqualTo("trace-1");
		assertThat(response.fieldErrors()).isEmpty();
	}

	@Test
	void ofWithFieldErrorsKeepsThem() {
		List<ErrorResponse.FieldError> fields = List.of(new ErrorResponse.FieldError("email", "must not be blank"));

		ErrorResponse response = ErrorResponse.of("INVALID_INPUT", "invalid", fields, "trace-2");

		assertThat(response.fieldErrors()).hasSize(1);
		assertThat(response.fieldErrors().get(0).field()).isEqualTo("email");
	}
}
