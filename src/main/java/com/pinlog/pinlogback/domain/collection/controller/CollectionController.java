package com.pinlog.pinlogback.domain.collection.controller;

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

import com.pinlog.pinlogback.domain.collection.dto.CollectionAddRecordsRequest;
import com.pinlog.pinlogback.domain.collection.dto.CollectionCreateRequest;
import com.pinlog.pinlogback.domain.collection.dto.CollectionRenameRequest;
import com.pinlog.pinlogback.domain.collection.dto.CollectionSummaryResponse;
import com.pinlog.pinlogback.domain.collection.service.CollectionService;
import com.pinlog.pinlogback.global.response.CursorPage;
import com.pinlog.pinlogback.global.security.LoginMember;
import com.pinlog.pinlogback.global.security.MemberPrincipal;

import jakarta.validation.Valid;

/**
 * Collection API(공개 API 명세 2.5·7장). 타인 공개 조회 응답은 S15P11A705-71에서 붙는다.
 */
@RestController
@RequestMapping("/v1/collections")
public class CollectionController {

	private final CollectionService collectionService;

	public CollectionController(CollectionService collectionService) {
		this.collectionService = collectionService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CollectionSummaryResponse create(@LoginMember MemberPrincipal me,
		@Valid @RequestBody CollectionCreateRequest request) {
		return collectionService.create(me.memberId(), request);
	}

	@GetMapping
	public CursorPage<CollectionSummaryResponse> listMine(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size) {
		return collectionService.listMine(me.memberId(), cursor, size);
	}

	/**
	 * 소유 여부에 따라 소유자용({@code CollectionDetailResponse})·공개용
	 * ({@code PublicCollectionDetailResponse}) 서로 다른 DTO가 반환된다(BD-13). 두 타입은 상속
	 * 관계가 없으므로 선언 타입은 Object다 — envelope는 런타임 advice가 감싼다.
	 */
	@GetMapping("/{collectionId}")
	public Object detail(@LoginMember MemberPrincipal me,
		@PathVariable Long collectionId,
		@RequestParam(required = false) String recordCursor,
		@RequestParam(required = false) Integer recordSize) {
		return collectionService.getDetail(me.memberId(), collectionId, recordCursor, recordSize);
	}

	@PatchMapping("/{collectionId}")
	public CollectionSummaryResponse rename(@LoginMember MemberPrincipal me,
		@PathVariable Long collectionId, @Valid @RequestBody CollectionRenameRequest request) {
		return collectionService.rename(me.memberId(), collectionId, request.title());
	}

	@PostMapping("/{collectionId}/records")
	public CollectionSummaryResponse addRecords(@LoginMember MemberPrincipal me,
		@PathVariable Long collectionId, @Valid @RequestBody CollectionAddRecordsRequest request) {
		return collectionService.addRecords(me.memberId(), collectionId, request);
	}

	@DeleteMapping("/{collectionId}/records/{recordId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeRecord(@LoginMember MemberPrincipal me, @PathVariable Long collectionId,
		@PathVariable Long recordId) {
		collectionService.removeRecord(me.memberId(), collectionId, recordId);
	}

	@DeleteMapping("/{collectionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCollection(@LoginMember MemberPrincipal me, @PathVariable Long collectionId) {
		collectionService.deleteCollection(me.memberId(), collectionId);
	}
}
