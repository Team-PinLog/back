package com.pinlog.pinlogback.domain.feed.dto;

import java.util.List;
import java.util.UUID;

import com.pinlog.pinlogback.global.common.InputLimits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * CLICK·SAVE 수집 요청(API 명세 10.2).
 *
 * <p><b>{@code memberId}를 받는 자리가 없다.</b> 인증 컨텍스트에서만 결정된다 — 본문으로 받으면
 * 타인 이벤트를 위조할 수 있다(feed-event 4장).
 *
 * <p>배열 크기 위반은 초과분만 잘라내지 않고 요청 전체를 400으로 거절한다. 잘라내면 클라이언트가
 * 조용한 유실을 인지할 방법이 없다. 반면 <b>개별 항목</b>의 무효(없는 {@code collectionId})는
 * 조용히 버리고 204다 — 두 규칙이 갈리는 지점이다(feed-tests E5·E9).
 *
 * @param requestId Feed 응답에서 받은 Session 식별자. 실재 Session인지는 검증하지 않고 UUID 형식만 본다
 */
public record FeedEventCollectRequest(
	@NotNull UUID requestId,
	@NotEmpty @Size(max = InputLimits.FEED_EVENTS_MAX) @Valid List<FeedEventItemRequest> events
) {
}
