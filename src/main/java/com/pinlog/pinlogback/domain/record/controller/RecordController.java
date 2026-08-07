package com.pinlog.pinlogback.domain.record.controller;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import com.pinlog.pinlogback.domain.record.dto.ContextCreateRequest;
import com.pinlog.pinlogback.domain.record.dto.ContextMutationResponse;
import com.pinlog.pinlogback.domain.record.dto.ContextSort;
import com.pinlog.pinlogback.domain.record.dto.ContextUpdateRequest;
import com.pinlog.pinlogback.domain.record.dto.MapKeywordsResponse;
import com.pinlog.pinlogback.domain.record.dto.MapResponse;
import com.pinlog.pinlogback.domain.record.dto.RecentRecordCardResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordByPlaceResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateRequest;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordDetailResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordSaveResult;
import com.pinlog.pinlogback.domain.record.service.RecordDeletionService;
import com.pinlog.pinlogback.domain.record.service.RecordService;
import com.pinlog.pinlogback.global.response.CursorPage;
import com.pinlog.pinlogback.global.security.authentication.LoginMember;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;

import jakarta.validation.Valid;

/**
 * Record·Context API(공개 API 명세 2.3·4.2). context-path(/api/core)는 인프라 고정값이므로
 * 여기에는 버전 세그먼트(v1)만 명시한다.
 */
@RestController
@RequestMapping("/v1/records")
public class RecordController {

	private final RecordService recordService;
	private final RecordDeletionService recordDeletionService;

	public RecordController(RecordService recordService, RecordDeletionService recordDeletionService) {
		this.recordService = recordService;
		this.recordDeletionService = recordDeletionService;
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
		@RequestParam(required = false) BigDecimal neLng,
		@RequestParam(required = false) String keyword) {
		return recordService.map(me.memberId(), swLat, swLng, neLat, neLng, keyword);
	}

	/**
	 * 지도에 보이는 범위의 Keyword 상위 5건(S15P11A705-388). 검색창 밑 추천 칩이 쓴다.
	 *
	 * <p>bbox 계약은 {@link #map}과 같다. 개수는 5 고정이라 파라미터가 없다.
	 *
	 * <p>리터럴 세그먼트가 두 개라 {@code /{recordId}}와 충돌하지 않는다.
	 */
	@GetMapping("/map/keywords")
	public MapKeywordsResponse mapKeywords(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) BigDecimal swLat,
		@RequestParam(required = false) BigDecimal swLng,
		@RequestParam(required = false) BigDecimal neLat,
		@RequestParam(required = false) BigDecimal neLng) {
		return recordService.mapKeywords(me.memberId(), swLat, swLng, neLat, neLng);
	}

	@GetMapping("/by-place")
	public RecordByPlaceResponse byPlace(@LoginMember MemberPrincipal me,
		@RequestParam String kakaoPlaceId) {
		return recordService.getByKakaoPlaceId(me.memberId(), kakaoPlaceId);
	}

	/**
	 * 최근 7일 안에 만든 내 Record 목록(명세 5.9). 홈 화면 "최근 기록" 영역이 쓴다.
	 *
	 * <p>기간·정렬 파라미터가 없다. 창은 서버가 7일로 고정하고 정렬은 최신순 고정이다 — "최근"이 곧
	 * 정렬이라, 오래된순으로 뒤집을 수 있는 {@code /recent}는 이름과 동작이 어긋난다.
	 *
	 * <p>{@code size} 기본값이 1이라 {@code required = false}로 받아 서비스가 정규화한다. 여기에
	 * {@code defaultValue = "1"}을 두면 상한·하한 접기가 컨트롤러와 서비스로 나뉜다.
	 *
	 * <p><b>이 매핑이 {@code /{recordId}}보다 앞서 선언되어야 하는 것은 아니다.</b> Spring MVC가
	 * 리터럴 세그먼트를 경로 변수보다 구체적인 패턴으로 보고 먼저 고른다. 다만 매핑이 없을 때는
	 * {@code "recent"}가 {@code Long} 변환에 실패해 404가 아니라 400이 나온다 — 이 경로를 지우면
	 * 그 400이 조용히 돌아온다.
	 */
	@GetMapping("/recent")
	public CursorPage<RecentRecordCardResponse> recent(@LoginMember MemberPrincipal me,
		@RequestParam(required = false) String cursor,
		@RequestParam(required = false) Integer size) {
		return recordService.listRecent(me.memberId(), cursor, size);
	}

	/** {@code contexts}의 기본 정렬은 최초 작성 시각 오름차순이다(명세 5.2, BD-25·BD-46). */
	@GetMapping("/{recordId}")
	public RecordDetailResponse detail(@LoginMember MemberPrincipal me, @PathVariable Long recordId,
		@RequestParam(defaultValue = "CREATED_AT_ASC") ContextSort contextSort) {
		return recordService.getDetail(me.memberId(), recordId, contextSort);
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

	@DeleteMapping("/{recordId}/contexts/{contextId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteContext(@LoginMember MemberPrincipal me, @PathVariable Long recordId,
		@PathVariable Long contextId) {
		recordDeletionService.deleteContext(me.memberId(), recordId, contextId);
	}

	@DeleteMapping("/{recordId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRecord(@LoginMember MemberPrincipal me, @PathVariable Long recordId) {
		recordDeletionService.deleteRecord(me.memberId(), recordId);
	}

	@DeleteMapping("/{recordId}/force")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void forceDeleteRecord(@LoginMember MemberPrincipal me, @PathVariable Long recordId) {
		recordDeletionService.forceDeleteRecord(me.memberId(), recordId);
	}
}
