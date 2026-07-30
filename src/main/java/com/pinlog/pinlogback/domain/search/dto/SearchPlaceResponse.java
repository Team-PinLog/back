package com.pinlog.pinlogback.domain.search.dto;

import java.math.BigDecimal;

/**
 * 검색 결과 카드의 장소(API 명세 6.1).
 *
 * <p>{@code domain/record}의 {@code PlaceSummaryResponse}를 재사용하지 않는다. 그쪽은 Record 상세가
 * 필요로 하는 필드를 전부 담은 상위집합이고, 여기 계약은 다섯 개다. 상위집합을 그대로 쓰면 상세
 * 응답에 필드가 늘 때마다 <b>검색 응답이 조용히 따라 넓어진다</b> — 경계마다 DTO를 따로 두는
 * 규약(BD-13)이 막으려는 것이 그것이다.
 *
 * @param placeId 장소 마스터 id
 * @param name 장소명
 * @param address 지번 주소
 * @param lat 위도. {@code bounds} 계산의 입력이기도 하다
 * @param lng 경도
 */
public record SearchPlaceResponse(
	Long placeId,
	String name,
	String address,
	BigDecimal lat,
	BigDecimal lng
) {
}
