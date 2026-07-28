package com.pinlog.pinlogback.domain.record.dto;

import java.math.BigDecimal;

/**
 * 지도 마커(API 명세 4.2). JPQL 생성자 표현식으로 직접 조회한다.
 */
public record MapMarkerResponse(Long recordId, Long placeId, String name, BigDecimal lat, BigDecimal lng) {
}
