package com.pinlog.pinlogback.domain.record.controller;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pinlog.pinlogback.domain.record.dto.ContextCreateRequest;
import com.pinlog.pinlogback.domain.record.dto.ContextMutationResponse;
import com.pinlog.pinlogback.domain.record.dto.ContextUpdateRequest;
import com.pinlog.pinlogback.domain.record.dto.MapResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordByPlaceResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateRequest;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordDetailResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordSaveResult;
import com.pinlog.pinlogback.domain.record.service.RecordService;
import com.pinlog.pinlogback.global.security.LoginMember;
import com.pinlog.pinlogback.global.security.MemberPrincipal;

import jakarta.validation.Valid;

/**
 * Record·Context API(공개 API 명세 2.3·4.2). context-path(/api/core)는 인프라 고정값이므로
 * 여기에는 버전 세그먼트(v1)만 명시한다.
 */
@RestController
@RequestMapping("/v1/records")
public class RecordController {

	private final RecordService recordService;

	public RecordController(RecordService recordService) {
		this.recordService = recordService;
	}

	@PostMapping
	public ResponseEntity<RecordCreateResponse> create(@LoginMember MemberPrincipal me,
		@Valid @RequestBody RecordCreateRequest request) {
		RecordCreateResponse response = recordService.create(me.memberId(), request);
		HttpStatus status = response.result() == RecordSaveResult.RECORD_CREATED
			? HttpStatus.CREATED
			: HttpStatus.OK;
		return ResponseEntity.status(status).body(response);
	}

	@GetMapping("/map")
	public MapResponse map(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) BigDecimal swLat,
		@RequestParam(required = false) BigDecimal swLng,
		@RequestParam(required = false) BigDecimal neLat,
		@RequestParam(required = false) BigDecimal neLng) {
		return recordService.map(me.memberId(), swLat, swLng, neLat, neLng);
	}

	@GetMapping("/by-place")
	public RecordByPlaceResponse byPlace(@LoginMember MemberPrincipal me,
		@RequestParam String kakaoPlaceId) {
		return recordService.getByKakaoPlaceId(me.memberId(), kakaoPlaceId);
	}

	@GetMapping("/{recordId}")
	public RecordDetailResponse detail(@LoginMember MemberPrincipal me, @PathVariable Long recordId) {
		return recordService.getDetail(me.memberId(), recordId);
	}

	@PostMapping("/{recordId}/contexts")
	@ResponseStatus(HttpStatus.CREATED)
	public ContextMutationResponse addContext(@LoginMember MemberPrincipal me, @PathVariable Long recordId,
		@Valid @RequestBody ContextCreateRequest request) {
		return recordService.addContext(me.memberId(), recordId, request.body());
	}

	@PatchMapping("/{recordId}/contexts/{contextId}")
	public ContextMutationResponse replaceContext(@LoginMember MemberPrincipal me, @PathVariable Long recordId,
		@PathVariable Long contextId, @Valid @RequestBody ContextUpdateRequest request) {
		return recordService.replaceContext(me.memberId(), recordId, contextId, request.body());
	}
}
