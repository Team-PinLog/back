package com.pinlog.pinlogback.domain.record.dto;

import java.time.Instant;
import java.util.List;

/**
 * POST /records 응답(API 명세 5.1). CONTEXT_ADDED일 때 contexts는 기존 것을 포함한
 * 활성 Context 전체다.
 */
public record RecordCreateResponse(
	RecordSaveResult result,
	Long recordId,
	PlaceSummaryResponse place,
	List<ContextResponse> contexts,
	List<String> keywords,
	Instant createdAt
) {

	public static RecordCreateResponse of(RecordSaveResult result, RecordDetailResponse detail) {
		return new RecordCreateResponse(
			result,
			detail.recordId(),
			detail.place(),
			detail.contexts(),
			detail.keywords(),
			detail.createdAt()
		);
	}
}
