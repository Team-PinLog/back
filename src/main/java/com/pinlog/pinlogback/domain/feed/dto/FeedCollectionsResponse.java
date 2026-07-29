package com.pinlog.pinlogback.domain.feed.dto;

import java.util.List;
import java.util.UUID;

import com.pinlog.pinlogback.global.response.CursorPage;

/**
 * Feed 목록 응답(API 명세 10.1).
 *
 * <p>{@code items}·{@code nextCursor}·{@code hasNext}는 공통 커서 계약(명세 1.4)의 세 필드
 * 그대로이고, {@code requestId}만 <b>같은 층에 하나 더</b> 붙는다. 커서에 인코딩하거나 항목마다
 * 반복하지 않는다(feed-tests Q7) — Session 식별자는 페이지 전체에 하나이고, 커서에 넣으면
 * 클라이언트가 이벤트 보고에 쓸 값을 커서에서 파내야 한다.
 *
 * <p>{@link CursorPage}를 필드로 품지 않고 펼쳐 담는 이유는 wire 계약 때문이다. 품으면
 * {@code data.page.items}처럼 한 겹 깊어져 다른 목록 응답과 형태가 갈린다. 페이지 계산 자체는
 * {@link CursorPage}가 그대로 하고 여기서는 옮겨 담기만 한다.
 *
 * @param requestId Feed Session 식별자. 클라이언트가 CLICK·SAVE 이벤트에 그대로 돌려보낸다
 */
public record FeedCollectionsResponse(
	UUID requestId,
	List<FeedCollectionItemResponse> items,
	String nextCursor,
	boolean hasNext
) {

	public static FeedCollectionsResponse of(UUID requestId, CursorPage<FeedCollectionItemResponse> page) {
		return new FeedCollectionsResponse(requestId, page.items(), page.nextCursor(), page.hasNext());
	}
}
