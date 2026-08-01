package com.pinlog.pinlogback.domain.feed.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.feed.dto.FeedCollectionsResponse;
import com.pinlog.pinlogback.domain.feed.dto.FeedEventCollectRequest;
import com.pinlog.pinlogback.domain.feed.service.FeedService;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

import jakarta.validation.Valid;

/**
 * Feed API(공개 API 명세 2.7·10장). 경로는 context-path({@code /api/core})가 앞에 붙는다.
 *
 * <p>작성자 공개 책장 탐색({@code GET /v1/feed/collections/{collectionId}/shelf}, 명세 8.1)은
 * 경로 접두어가 같지만 <b>이 클래스에 없다</b>. 추천 점수를 하나도 거치지 않아 Follow 도메인의
 * {@code ShelfController}가 담는다 — 한 클래스에 두면 AI 파트가 소유하는 추천 파이프라인과
 * 소유 경계가 흐려진다(BD-43, S15P11A705-206).
 */
@RestController
@RequestMapping("/v1/feed")
public class FeedController {

	private final FeedService feedService;

	public FeedController(FeedService feedService) {
		this.feedService = feedService;
	}

	/**
	 * {@code size}에 Feed 전용 상한을 두지 않는다. 공통 {@code CursorPage.normalizeSize}가
	 * 기본값 20·상한 100으로 보정하며, 범위 밖 값은 400이 아니라 보정이다 — "서버 방어 상한의
	 * 답은 하나"라는 S15P11A705-117 규약이다.
	 */
	@GetMapping("/collections")
	public FeedCollectionsResponse recommend(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size) {
		return feedService.recommend(me.memberId(), cursor, size);
	}

	/**
	 * CLICK·SAVE 수집. {@code memberId}는 본문이 아니라 인증 컨텍스트에서 온다 — 본문으로 받으면
	 * 타인 이벤트를 위조할 수 있다(feed-event 4장).
	 */
	@PostMapping("/events")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void collect(@LoginMember MemberPrincipal me,
		@Valid @RequestBody FeedEventCollectRequest request) {
		feedService.collect(me.memberId(), request);
	}
}
