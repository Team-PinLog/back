package com.pinlog.pinlogback.domain.record.dto;

import java.util.List;

import com.pinlog.pinlogback.global.response.BoundsResponse;

/**
 * GET /records/map 응답(API 명세 4.2). 마커가 없으면 bounds는 명시적 null이다 —
 * 프론트가 fitBounds 분기에 사용한다.
 */
public record MapResponse(BoundsResponse bounds, List<MapMarkerResponse> items) {
}
