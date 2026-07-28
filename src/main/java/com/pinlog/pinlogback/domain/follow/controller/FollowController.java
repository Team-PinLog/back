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

import com.pinlog.pinlogback.domain.follow.dto.FollowAliasUpdateRequest;
import com.pinlog.pinlogback.domain.follow.dto.FollowCreateRequest;
import com.pinlog.pinlogback.domain.follow.dto.FollowResponse;
import com.pinlog.pinlogback.domain.follow.dto.FollowedCollectionResponse;
import com.pinlog.pinlogback.domain.follow.service.FollowService;
import com.pinlog.pinlogback.global.response.CursorPage;
import com.pinlog.pinlogback.global.security.LoginMember;
import com.pinlog.pinlogback.global.security.MemberPrincipal;

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

	@GetMapping
	public CursorPage<FollowResponse> listMine(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size) {
		return followService.listMine(me.memberId(), cursor, size);
	}

	@GetMapping("/{followId}/collections")
	public CursorPage<FollowedCollectionResponse> followedCollections(@LoginMember MemberPrincipal me,
		@PathVariable Long followId,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size) {
		return followService.listFollowedCollections(me.memberId(), followId, cursor, size);
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
