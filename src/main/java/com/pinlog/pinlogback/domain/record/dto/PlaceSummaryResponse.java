package com.pinlog.pinlogback.domain.record.dto;

import java.math.BigDecimal;

import com.pinlog.pinlogback.domain.place.entity.Place;

/**
 * 소유자 응답의 장소 요약. 표시는 도로명 우선·없으면 지번이고 최신 정보는 placeUrl로
 * 카카오맵에 위임하므로(데이터모델 2.3) 프론트가 필요한 필드를 모두 담는다.
 */
public record PlaceSummaryResponse(
	Long placeId,
	String kakaoPlaceId,
	String name,
	String address,
	String roadAddress,
	String phone,
	String placeUrl,
	BigDecimal lat,
	BigDecimal lng
) {

	public static PlaceSummaryResponse from(Place place) {
		return new PlaceSummaryResponse(
			place.getId(),
			place.getKakaoPlaceId(),
			place.getName(),
			place.getAddress(),
			place.getRoadAddress(),
			place.getPhone(),
			place.getPlaceUrl(),
			place.getLat(),
			place.getLng()
		);
	}
}
