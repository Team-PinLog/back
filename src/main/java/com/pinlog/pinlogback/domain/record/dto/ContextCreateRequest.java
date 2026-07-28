package com.pinlog.pinlogback.domain.record.dto;

import com.pinlog.pinlogback.global.common.InputLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContextCreateRequest(
	@NotBlank @Size(max = InputLimits.CONTEXT_BODY_MAX) String body
) {
}
