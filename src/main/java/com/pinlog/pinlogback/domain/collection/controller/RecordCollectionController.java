package com.pinlog.pinlogback.domain.collection.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.collection.dto.CollectionSort;
import com.pinlog.pinlogback.domain.collection.dto.RecordCollectionCardResponse;
import com.pinlog.pinlogback.domain.collection.service.CollectionService;
import com.pinlog.pinlogback.global.response.CursorPage;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

/**
 * Record가 담긴 내 Collection 목록(공개 API 명세 5.10). 경로는 Record 하위지만 반환 자원이
 * Collection이라 collection 도메인에 둔다 — {@code RecordController}에 붙이면 record 도메인이
 * Collection 서비스를 끌어와야 한다.
 */
@RestController
@RequestMapping("/v1/records/{recordId}/collections")
public class RecordCollectionController {

	private final CollectionService collectionService;

	public RecordCollectionController(CollectionService collectionService) {
		this.collectionService = collectionService;
	}

	@GetMapping
	public CursorPage<RecordCollectionCardResponse> listByRecord(@LoginMember MemberPrincipal me,
		@PathVariable Long recordId,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size,
		@RequestParam(defaultValue = "CREATED_AT_ASC") CollectionSort sort) {
		return collectionService.listByRecord(me.memberId(), recordId, cursor, size, sort);
	}
}
