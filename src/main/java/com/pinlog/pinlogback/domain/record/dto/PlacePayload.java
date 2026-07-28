package com.pinlog.pinlogback.domain.record.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 프론트가 카카오 로컬 API 응답을 그대로 전달하는 장소 데이터(API 명세 5.1).
 * 좌표는 카카오가 문자열로 응답하므로 범위 검증이 파싱 오류를 잡아준다.
 */
public record PlacePayload(
	@NotBlank @Size(max = 50) String kakaoPlaceId,
	@NotBlank @Size(max = 100) String name,
	@NotBlank @Size(max = 200) String address,
	@Size(max = 200) String roadAddress,
	@Size(max = 30) String phone,
	@Size(max = 300) String placeUrl,
	@NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
	@NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng
) {
}
