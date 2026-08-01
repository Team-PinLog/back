package com.pinlog.pinlogback.domain.record.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.record.entity.Record;

/**
 * 소유자용 Record 상세(API 명세 11.1). keywords는 활성 Context 전체의 Keyword 집계값이며
 * 소유자 범위({@code PUBLIC + PRIVATE_ONLY})다 — 호출부가 {@code ContextKeywordRepository}로
 * 채운다. AI 처리 전이거나 생성 직후에는 빈 배열이 정상이다(명세 1.3).
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

	public static RecordDetailResponse of(Record record, Place place, List<ContextResponse> contexts,
		List<String> keywords) {
		return of(record, place, contexts, keywords, null);
	}

	public static RecordDetailResponse of(Record record, Place place, List<ContextResponse> contexts,
		List<String> keywords, Instant addedToCollectionAt) {
		return new RecordDetailResponse(
			record.getId(),
			PlaceSummaryResponse.from(place),
			contexts,
			keywords,
			record.getCreatedAt(),
			addedToCollectionAt
		);
	}
}
