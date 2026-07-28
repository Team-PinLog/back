package com.pinlog.pinlogback.domain.record.dto;

/**
 * GET /records/by-place 응답(API 명세 5.3). 내 활성 Record가 없으면 record가 null인 200이다 —
 * 미저장 장소 조회는 정상 흐름이므로 404가 아니다.
 */
public record RecordByPlaceResponse(RecordDetailResponse record) {
}
