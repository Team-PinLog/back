package com.pinlog.pinlogback.domain.feed.dto;

import com.pinlog.pinlogback.domain.feed.entity.FeedEventType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 클라이언트가 보고하는 이벤트 한 건(API 명세 10.2).
 *
 * @param event {@code CLICK} 또는 {@code SAVE}. {@code IMPRESSION}은 서버가 기록하는 값이므로
 *              클라이언트가 보내면 400으로 거부한다(feed-event 4장)
 * @param collectionId 대상 Collection. 실재하지 않으면 해당 건만 조용히 버린다
 * @param placeId Collection 안의 특정 Place를 눌렀거나 저장한 경우에만
 * @param position Feed 응답에서 받은 값을 그대로 돌려보낸다
 */
public record FeedEventItemRequest(
	@NotNull FeedEventType event,
	@NotNull Long collectionId,
	Long placeId,
	@PositiveOrZero Integer position
) {
}
