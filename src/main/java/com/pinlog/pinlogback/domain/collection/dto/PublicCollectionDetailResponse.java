package com.pinlog.pinlogback.domain.collection.dto;

import java.time.Instant;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.global.response.CursorPage;

/**
 * 타인 공개 Collection 상세(API 명세 7.3, 데이터모델 5.2). 소유자용
 * {@code CollectionDetailResponse}와 별개 타입이다 — 상속·조건부 직렬화는 부모에 필드가 추가될 때
 * 조용히 노출되므로 쓰지 않는다(BD-13). 작성자 신원 정보(내부 ID·이메일 등)를 담는 필드가 없다.
 */
public record PublicCollectionDetailResponse(
	Long collectionId,
	String title,
	boolean ownedByMe,
	FollowStatusResponse follow,
	CursorPage<PublicRecordCardResponse> records,
	Instant publishedAt,
	Instant createdAt,
	Instant updatedAt
) {

	public static PublicCollectionDetailResponse of(Collection collection, FollowStatusResponse follow,
		CursorPage<PublicRecordCardResponse> records) {
		return new PublicCollectionDetailResponse(
			collection.getId(),
			collection.getTitle(),
			false,
			follow,
			records,
			collection.getPublishedAt(),
			collection.getCreatedAt(),
			collection.getUpdatedAt()
		);
	}
}
