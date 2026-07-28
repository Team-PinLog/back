package com.pinlog.pinlogback.domain.record.dto;

import jakarta.validation.constraints.NotBlank;

public record ContextUpdateRequest(@NotBlank String body) {
}
