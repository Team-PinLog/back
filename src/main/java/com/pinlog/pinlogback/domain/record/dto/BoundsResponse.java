package com.pinlog.pinlogback.domain.record.dto;

import java.math.BigDecimal;

/**
 * 반환된 마커 전체를 포함하는 최소 사각형(API 명세 4.2). 마커 1개면 sw와 ne가 같은 점 사각형이다.
 */
public record BoundsResponse(BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng) {
}
