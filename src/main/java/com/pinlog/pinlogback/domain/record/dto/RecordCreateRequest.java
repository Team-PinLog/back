package com.pinlog.pinlogback.domain.record.dto;

import com.pinlog.pinlogback.global.common.InputLimits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecordCreateRequest(
	@NotNull @Valid PlacePayload place,
	@NotBlank @Size(max = InputLimits.CONTEXT_BODY_MAX) String contextBody
) {
}
