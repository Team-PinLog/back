package com.pinlog.pinlogback.domain.collection.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

public record CollectionAddRecordsRequest(@NotEmpty List<Long> recordIds) {
}
