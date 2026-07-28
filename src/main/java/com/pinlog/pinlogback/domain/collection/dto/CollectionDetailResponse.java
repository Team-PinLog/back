package com.pinlog.pinlogback.domain.collection.dto;

import java.time.Instant;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.record.dto.RecordDetailResponse;
import com.pinlog.pinlogback.global.response.CursorPage;

/**
 * Collection 상세 통합 조회 응답(API 명세 7.3·11.3). 소유자 조회는 follow가 명시적 null이다.
 */
public record CollectionDetailResponse(
	Long collectionId,
	String title,
	boolean ownedByMe,
	FollowStatusResponse follow,
	CursorPage<RecordDetailResponse> records,
	Instant publishedAt,
	Instant createdAt,
	Instant updatedAt
) {

	public static CollectionDetailResponse forOwner(Collection collection,
		CursorPage<RecordDetailResponse> records) {
		return new CollectionDetailResponse(
			collection.getId(),
			collection.getTitle(),
			true,
			null,
			records,
			collection.getPublishedAt(),
			collection.getCreatedAt(),
			collection.getUpdatedAt()
		);
	}
}
