package com.pinlog.pinlogback.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

	@Test
	void frameworkErrorCodesCarryTheirHttpStatus() {
		assertThat(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	@Test
	void codeIsTheEnumName() {
		assertThat(ErrorCode.METHOD_NOT_ALLOWED.getCode()).isEqualTo("METHOD_NOT_ALLOWED");
		assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
	}

	@Test
	void existingCodesKeepTheirStatus() {
		assertThat(ErrorCode.INVALID_INPUT.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ErrorCode.INTERNAL_ERROR.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void everyCodeHasANonBlankMessage() {
		for (ErrorCode code : ErrorCode.values()) {
			assertThat(code.getMessage()).as(code.name()).isNotBlank();
		}
	}
}
