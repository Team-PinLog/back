package com.pinlog.pinlogback.domain.feed.repository;

import java.time.Instant;

/**
 * 재검증을 통과한 Collection의 표시용 데이터. <b>소유자 식별 정보를 담는 자리가 없다</b> —
 * 응답 조립 경로에 {@code member_id}가 흘러들 방법 자체를 없앤다(API 명세 10.1, BD-13).
 *
 * <p>다양성 조정에 필요한 소유자 id는 {@code FeedCandidate}가 들고 있고, 그 타입은 응답 DTO로
 * 변환되지 않는다.
 */
public record FeedCollectionCard(long collectionId, String title, int recordCount,
	String coverImageUrl, Instant createdAt) {
}
