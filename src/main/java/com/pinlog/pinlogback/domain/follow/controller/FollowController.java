package com.pinlog.pinlogback.domain.follow.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.collection.dto.CollectionSort;
import com.pinlog.pinlogback.domain.follow.dto.FollowAliasUpdateRequest;
import com.pinlog.pinlogback.domain.follow.dto.FollowCreateRequest;
import com.pinlog.pinlogback.domain.follow.dto.FollowResponse;
import com.pinlog.pinlogback.domain.follow.dto.FollowedCollectionResponse;
import com.pinlog.pinlogback.domain.follow.service.FollowService;
import com.pinlog.pinlogback.global.response.CursorPage;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

import jakarta.validation.Valid;

/**
 * Follow API(공개 API 명세 2.6·8~9장). Library는 전용 엔드포인트 없이 이 API 조합으로 구성된다.
 */
@RestController
@RequestMapping("/v1/follows")
public class FollowController {

	private final FollowService followService;

	public FollowController(FollowService followService) {
		this.followService = followService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public FollowResponse follow(@LoginMember MemberPrincipal me,
		@Valid @RequestBody FollowCreateRequest request) {
		return followService.follow(me.memberId(), request.collectionId());
	}

	/**
	 * {@code collectionSize}를 주면 항목마다 그 책장 Collection의 첫 페이지가 실린다(명세 9.2,
	 * S15P11A705-244). 없으면 기존 응답 그대로다 — 항목 타입 자체가 갈리므로 반환은 와일드카드다.
	 *
	 * <p>{@code collectionSort}는 동봉 페이지의 방향이다(BD-46). 팔로우 축 정렬(최신순 고정)이
	 * 아니라서 이름에 접두어를 붙인다 — {@code collectionSize}와 같은 중첩 축 규약이다. 동봉
	 * 페이지의 커서를 9.3이 같은 {@code sort}로 이어받는다.
	 */
	@GetMapping
	public CursorPage<?> listMine(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size,
		@RequestParam(required = false) Integer collectionSize,
		@RequestParam(defaultValue = "CREATED_AT_ASC") CollectionSort collectionSort) {
		if (collectionSize == null) {
			return followService.listMine(me.memberId(), cursor, size);
		}
		return followService.listMineWithCollections(me.memberId(), cursor, size, collectionSize,
			collectionSort);
	}

	/** 기본 정렬은 오래된순이고 {@code sort}는 7.2와 같은 규칙이다(명세 9.3, BD-46). */
	@GetMapping("/{followId}/collections")
	public CursorPage<FollowedCollectionResponse> followedCollections(@LoginMember MemberPrincipal me,
		@PathVariable Long followId,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size,
		@RequestParam(defaultValue = "CREATED_AT_ASC") CollectionSort sort) {
		return followService.listFollowedCollections(me.memberId(), followId, cursor, size, sort);
	}

	@PatchMapping("/{followId}")
	public FollowResponse changeAlias(@LoginMember MemberPrincipal me, @PathVariable Long followId,
		@RequestBody FollowAliasUpdateRequest request) {
		return followService.changeAlias(me.memberId(), followId, request.alias());
	}

	@DeleteMapping("/{followId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unfollow(@LoginMember MemberPrincipal me, @PathVariable Long followId) {
		followService.unfollow(me.memberId(), followId);
	}
}
