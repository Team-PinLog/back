package com.pinlog.pinlogback.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BusinessExceptionTest {

	private static final class SampleException extends BusinessException {
		private SampleException() {
			super(ErrorCode.RESOURCE_NOT_FOUND);
		}
	}

	@Test
	void exposesCodeAndStatusFromErrorCode() {
		BusinessException ex = new SampleException();

		assertThat(ex.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
		assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
	}
}
