package com.pinlog.pinlogback.domain.record.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.record.entity.Record;

/**
 * 소유자용 Record 상세(API 명세 11.1). keywords는 AI 파트가 채우기 전까지 빈 배열이 정상이다.
 */
public record RecordDetailResponse(
	Long recordId,
	PlaceSummaryResponse place,
	List<ContextResponse> contexts,
	List<String> keywords,
	Instant createdAt
) {

	public static RecordDetailResponse of(Record record, Place place, List<ContextResponse> contexts) {
		return new RecordDetailResponse(
			record.getId(),
			PlaceSummaryResponse.from(place),
			contexts,
			List.of(),
			record.getCreatedAt()
		);
	}
}
