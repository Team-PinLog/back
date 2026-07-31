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
 * <p><b>{@code CollectionController}와 경로를 나눠 쓰면서 클래스를 따로 두는 이유</b>는 소유
 * 도메인이 다르기 때문이다. 이 Endpoint는 요청자의 팔로우 상태를 함께 내보내므로 Follow 도메인의
 * 관심사이고, {@code CollectionController}는 Collection 소유자 유스케이스를 담는다. 경로가
 * {@code /v1/collections} 아래인 것은 <b>진입 키가 {@code collectionId}</b>라서다.
 *
 * <p>{@code FeedController}에 두지 않은 것도 같은 이유다 — 추천 계산을 하나도 거치지 않는다.
 */
@RestController
@RequestMapping("/v1/collections")
public class ShelfController {

	private final ShelfService shelfService;

	public ShelfController(ShelfService shelfService) {
		this.shelfService = shelfService;
	}

	@GetMapping("/{collectionId}/shelf")
	public ShelfResponse browse(@LoginMember MemberPrincipal me, @PathVariable Long collectionId,
		@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return shelfService.browse(me.memberId(), collectionId, cursor, size);
	}
}
