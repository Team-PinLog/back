package com.pinlog.pinlogback.domain.collection.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Collection 생성 요청(API 명세 7.1). Record 없는 Collection은 허용하지 않는다.
 */
public record CollectionCreateRequest(
	@NotBlank @Size(max = 20) String title,
	@NotEmpty List<Long> recordIds
) {
}
