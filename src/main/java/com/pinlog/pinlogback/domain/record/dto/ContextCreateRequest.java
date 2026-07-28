package com.pinlog.pinlogback.domain.record.dto;

import jakarta.validation.constraints.NotBlank;

public record ContextCreateRequest(@NotBlank String body) {
}
