package com.pinlog.pinlogback.domain.follow.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.collection.entity.Collection;

/**
 * 팔로우한 Shelf의 공개 Collection 목록 항목(API 명세 8.1·9.3). 작성자 신원은 포함하지 않고,
 * keywords는 AI 파트가 채우기 전까지 빈 배열이 정상이다.
 */
public record FollowedCollectionResponse(
	Long collectionId,
	String title,
	int recordCount,
	List<String> keywords,
	Instant createdAt
) {

	public static FollowedCollectionResponse from(Collection collection) {
		return new FollowedCollectionResponse(
			collection.getId(),
			collection.getTitle(),
			collection.getRecordCount(),
			List.of(),
			collection.getCreatedAt()
		);
	}
}
