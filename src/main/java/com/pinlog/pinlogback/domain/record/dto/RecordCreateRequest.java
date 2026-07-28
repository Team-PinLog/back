package com.pinlog.pinlogback.domain.record.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RecordCreateRequest(
	@NotNull @Valid PlacePayload place,
	@NotBlank String contextBody
) {
}
