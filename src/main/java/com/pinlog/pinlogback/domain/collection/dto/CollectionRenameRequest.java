package com.pinlog.pinlogback.domain.collection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CollectionRenameRequest(@NotBlank @Size(max = 20) String title) {
}
