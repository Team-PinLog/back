package com.pinlog.pinlogback.domain.follow.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.follow.dto.ShelfResponse;
import com.pinlog.pinlogback.domain.follow.service.ShelfService;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

/**
 * 작성자 공개 책장 탐색(API 명세 8.1). 경로는 context-path({@code /api/core})가 앞에 붙는다.
 *
 * <p><b>경로 접두어가 {@code /v1/feed}인데 Feed 도메인이 아니다.</b> 추천 점수·Profile·
 * {@code feed_event} 어느 것도 거치지 않으므로 구현은 Follow 도메인에 있고, 경로만 공용 계약
 * (명세 2.7·8.1)을 그대로 따른다. 계약을 먼저 고쳐 {@code /v1/collections} 아래로 옮기는 안을
 * 검토했으나, 라이브러리 집계 조회와 식별자 은닉 재검토가 함께 열려 있어 경로를 두 번 바꾸지
 * 않도록 한 번에 정하기로 했다(BD-43).
 *
 * <p>{@code FeedController}에 매핑을 얹지 않은 이유도 같다 — 경로가 같은 접두어를 쓰더라도
 * 추천 파이프라인과 한 클래스에 두면 소유 경계가 흐려진다.
 */
@RestController
@RequestMapping("/v1/feed")
public class ShelfController {

	private final ShelfService shelfService;

	public ShelfController(ShelfService shelfService) {
		this.shelfService = shelfService;
	}

	@GetMapping("/collections/{collectionId}/shelf")
	public ShelfResponse browse(@LoginMember MemberPrincipal me, @PathVariable Long collectionId,
		@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return shelfService.browse(me.memberId(), collectionId, cursor, size);
	}
}
