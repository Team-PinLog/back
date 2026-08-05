package com.pinlog.pinlogback.domain.collection.dto;

import java.time.Instant;

import com.pinlog.pinlogback.domain.collection.entity.Collection;

/**
 * Collection 생성·목록·편집 응답의 요약 형태(API 명세 7.1·7.2).
 */
public record CollectionSummaryResponse(
	Long collectionId,
	String title,
	int recordCount,
	String coverImageUrl,
	Instant publishedAt,
	Instant createdAt
) {

	public static CollectionSummaryResponse from(Collection collection) {
		return new CollectionSummaryResponse(
			collection.getId(),
			collection.getTitle(),
			collection.getRecordCount(),
			collection.getCoverImageUrl(),
			collection.getPublishedAt(),
			collection.getCreatedAt()
		);
	}
}
