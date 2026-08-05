package com.pinlog.pinlogback.domain.follow.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.collection.entity.Collection;

/**
 * 팔로우한 Shelf의 공개 Collection 목록 항목(API 명세 8.1·9.3). 작성자 신원은 포함하지 않는다.
 * keywords는 담긴 Record들의 {@code PUBLIC} Keyword 집계값(BD-18)이며 호출부가
 * {@code ContextKeywordRepository.findCollectionKeywordsPublic}으로 채운다 — AI 처리 전에는
 * 빈 배열이 정상이다(명세 1.3).
 */
public record FollowedCollectionResponse(
	Long collectionId,
	String title,
	int recordCount,
	List<String> keywords,
	String coverImageUrl,
	Instant createdAt
) {

	public static FollowedCollectionResponse from(Collection collection, List<String> keywords) {
		return new FollowedCollectionResponse(
			collection.getId(),
			collection.getTitle(),
			collection.getRecordCount(),
			keywords,
			collection.getCoverImageUrl(),
			collection.getCreatedAt()
		);
	}
}
