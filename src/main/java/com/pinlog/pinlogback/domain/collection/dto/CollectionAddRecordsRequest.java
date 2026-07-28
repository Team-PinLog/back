package com.pinlog.pinlogback.domain.collection.dto;

import java.util.List;

import com.pinlog.pinlogback.global.common.InputLimits;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Collection에 Record를 추가하는 요청(API 명세 7.5). 한 번에 담을 수 있는 수는
 * {@link InputLimits#RECORD_IDS_MAX}로 제한한다.
 */
public record CollectionAddRecordsRequest(
	@NotEmpty @Size(max = InputLimits.RECORD_IDS_MAX) List<Long> recordIds
) {
}
