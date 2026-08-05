package com.pinlog.pinlogback.domain.record.dto;

import java.math.BigDecimal;

/**
 * 지도 마커(API 명세 4.2). JPQL 생성자 표현식으로 직접 조회한다.
 *
 * <p>{@code latestCollectionId}는 이 Record가 가장 최근에 담긴 Collection의 id이며, 어느
 * Collection에도 담기지 않았으면 {@code null}이다(프론트 마커 색상 구분용, S15P11A705-308).
 * 마커 조회와 별개의 배치 질의로 채우므로 JPQL은 5개 인자 생성자를 쓴다.
 */
public record MapMarkerResponse(Long recordId, Long placeId, String name, BigDecimal lat, BigDecimal lng,
	Long latestCollectionId) {

	public MapMarkerResponse(Long recordId, Long placeId, String name, BigDecimal lat, BigDecimal lng) {
		this(recordId, placeId, name, lat, lng, null);
	}

	public MapMarkerResponse withLatestCollectionId(Long latestCollectionId) {
		return new MapMarkerResponse(recordId, placeId, name, lat, lng, latestCollectionId);
	}
}
