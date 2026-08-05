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
import com.pinlog.pinlogback.domain.collection.dto.CollectionDetailResponse;
import com.pinlog.pinlogback.domain.collection.dto.CollectionSort;
import com.pinlog.pinlogback.domain.collection.dto.CollectionSummaryResponse;
import com.pinlog.pinlogback.domain.collection.dto.CollectionUpdateRequest;
import com.pinlog.pinlogback.domain.collection.dto.PublicCollectionDetailResponse;
import com.pinlog.pinlogback.domain.collection.dto.RecordSort;
import com.pinlog.pinlogback.domain.collection.service.CollectionService;
import com.pinlog.pinlogback.global.response.CursorPage;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

	/** 기본 정렬은 오래된순이고 {@code sort}는 프론트가 상수로 고정해 보낸다(명세 7.2, BD-46). */
	@GetMapping
	public CursorPage<CollectionSummaryResponse> listMine(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size,
		@RequestParam(defaultValue = "CREATED_AT_ASC") CollectionSort sort) {
		return collectionService.listMine(me.memberId(), cursor, size, sort);
	}

	/**
	 * 소유 여부에 따라 소유자용({@code CollectionDetailResponse})·공개용
	 * ({@code PublicCollectionDetailResponse}) 서로 다른 DTO가 반환된다(BD-13). 두 타입은 상속
	 * 관계가 없으므로 선언 타입은 Object다 — envelope는 런타임 advice가 감싼다.
	 *
	 * <p>선언 타입이 {@code Object}라 springdoc이 introspect할 것이 없어 응답 스키마가 비어 버린다.
	 * {@code oneOf}로 두 DTO를 직접 알려 준다 — 런타임 계약(둘 중 하나)과 같은 형태라 문서와 실제가
	 * 어긋날 여지가 없다. 반환 타입 자체는 BD-13에 따라 {@code Object}로 둔다.
	 */
	@ApiResponse(responseCode = "200", content = @Content(schema = @Schema(
		oneOf = {CollectionDetailResponse.class, PublicCollectionDetailResponse.class})))
	@GetMapping("/{collectionId}")
	public Object detail(@LoginMember MemberPrincipal me,
		@PathVariable Long collectionId,
		@RequestParam(required = false) String recordCursor,
		@RequestParam(required = false) Integer recordSize,
		@RequestParam(defaultValue = "ADDED_AT_ASC") RecordSort recordSort) {
		return collectionService.getDetail(me.memberId(), collectionId, recordCursor, recordSize, recordSort);
	}

	/** 제목·표지 수정(명세 7.4). 보내지 않았거나 null인 필드는 기존 값을 유지한다. */
	@PatchMapping("/{collectionId}")
	public CollectionSummaryResponse update(@LoginMember MemberPrincipal me,
		@PathVariable Long collectionId, @Valid @RequestBody CollectionUpdateRequest request) {
		return collectionService.update(me.memberId(), collectionId, request);
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
