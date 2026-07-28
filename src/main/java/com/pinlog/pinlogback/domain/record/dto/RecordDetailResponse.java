package com.pinlog.pinlogback.domain.record.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.record.entity.Record;

/**
 * 소유자용 Record 상세(API 명세 11.1). keywords는 AI 파트가 채우기 전까지 빈 배열이 정상이다.
 * addedToCollectionAt은 Collection 상세 안에서만 채워지는 선택 필드라 null이면 생략한다.
 */
public record RecordDetailResponse(
	Long recordId,
	PlaceSummaryResponse place,
	List<ContextResponse> contexts,
	List<String> keywords,
	Instant createdAt,
	@JsonInclude(JsonInclude.Include.NON_NULL) Instant addedToCollectionAt
) {

	public static RecordDetailResponse of(Record record, Place place, List<ContextResponse> contexts) {
		return of(record, place, contexts, null);
	}

	public static RecordDetailResponse of(Record record, Place place, List<ContextResponse> contexts,
		Instant addedToCollectionAt) {
		return new RecordDetailResponse(
			record.getId(),
			PlaceSummaryResponse.from(place),
			contexts,
			List.of(),
			record.getCreatedAt(),
			addedToCollectionAt
		);
	}
}
