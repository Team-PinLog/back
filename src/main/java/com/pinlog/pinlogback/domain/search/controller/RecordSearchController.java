package com.pinlog.pinlogback.domain.search.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.search.dto.RecordSearchRequest;
import com.pinlog.pinlogback.domain.search.dto.RecordSearchResponse;
import com.pinlog.pinlogback.domain.search.service.RecordSearchService;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

import jakarta.validation.Valid;

/**
 * 개인 자연어 검색 API(공개 API 명세 2.4·6.1). context-path(/api/core)는 인프라 고정값이므로
 * 여기에는 버전 세그먼트(v1)만 명시한다.
 *
 * <p>조회인데 {@code POST}인 이유는 명세가 그렇게 정해서다 — 질의가 본문으로 오는 편이 길이·인코딩
 * 제약에서 자유롭고, 검색어가 URL과 로그에 남지 않는다.
 */
@RestController
@RequestMapping("/v1/search")
public class RecordSearchController {

	private final RecordSearchService recordSearchService;

	public RecordSearchController(RecordSearchService recordSearchService) {
		this.recordSearchService = recordSearchService;
	}

	@PostMapping("/records")
	public RecordSearchResponse searchRecords(@LoginMember MemberPrincipal me,
		@Valid @RequestBody RecordSearchRequest request) {
		return recordSearchService.search(me.memberId(), request);
	}
}
