package com.pinlog.pinlogback.domain.collection.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.collection.entity.Collection;

/**
 * Record가 담긴 내 Collection 목록의 항목 하나(API 명세 5.10).
 *
 * <p>{@link CollectionSummaryResponse}에 {@code keywords}만 더한 형태다. 그 DTO를 그대로 쓰지
 * 않는 이유는 사용처가 다르기 때문이다 — 생성(7.1)·이름 변경(7.4) 응답까지 키워드 집계 쿼리를
 * 끌고 다니게 된다.
 *
 * <p>{@code position}·{@code requestId}가 없다. 이 목록은 추천이 아니라 내 데이터 조회이므로
 * Feed Session에 속하지 않으며, 클라이언트가 Feed 이벤트를 보낼 자리도 없다.
 *
 * @param keywords 공개 가능한 {@code PUBLIC} Keyword. AI 처리가 끝나지 않았으면 빈 배열이며
 *                 <b>오류가 아니다</b>
 */
public record RecordCollectionCardResponse(
	Long collectionId,
	String title,
	int recordCount,
	List<String> keywords,
	String coverImageUrl,
	Instant publishedAt,
	Instant createdAt
) {

	public RecordCollectionCardResponse {
		keywords = List.copyOf(keywords);
	}

	public static RecordCollectionCardResponse of(Collection collection, List<String> keywords) {
		return new RecordCollectionCardResponse(
			collection.getId(),
			collection.getTitle(),
			collection.getRecordCount(),
			keywords,
			collection.getCoverImageUrl(),
			collection.getPublishedAt(),
			collection.getCreatedAt()
		);
	}
}
