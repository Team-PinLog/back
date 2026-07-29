package com.pinlog.pinlogback.domain.record.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.ai.repository.AiDerivedDataRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.dto.BoundsResponse;
import com.pinlog.pinlogback.domain.record.dto.ContextMutationResponse;
import com.pinlog.pinlogback.domain.record.dto.ContextResponse;
import com.pinlog.pinlogback.domain.record.dto.MapMarkerResponse;
import com.pinlog.pinlogback.domain.record.dto.MapResponse;
import com.pinlog.pinlogback.domain.record.dto.PlacePayload;
import com.pinlog.pinlogback.domain.record.dto.RecordByPlaceResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateRequest;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordDetailResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordSaveResult;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.global.exception.InvalidRequestException;
import com.pinlog.pinlogback.global.exception.ResourceNotFoundException;

/**
 * Record·Context 유스케이스. 사용자 식별자는 컨트롤러가 인증 경계에서 해석한 memberId를
 * 파라미터로 받는다(BD-14) — 인증이 붙어도 이 시그니처는 바뀌지 않는다.
 */
@Service
public class RecordService {

	private final PlaceRepository placeRepository;
	private final RecordRepository recordRepository;
	private final ContextRepository contextRepository;
	private final AiDerivedDataRepository aiDerivedDataRepository;

	public RecordService(PlaceRepository placeRepository, RecordRepository recordRepository,
		ContextRepository contextRepository, AiDerivedDataRepository aiDerivedDataRepository) {
		this.placeRepository = placeRepository;
		this.recordRepository = recordRepository;
		this.contextRepository = contextRepository;
		this.aiDerivedDataRepository = aiDerivedDataRepository;
	}

	/**
	 * Record 생성(데이터모델 6.1·6.3). 동일 Place에 내 활성 Record가 있으면 거절하지 않고
	 * Context 추가로 처리한다(BD-12).
	 *
	 * <p>조회 후 분기하지 않고 <b>먼저 충돌 없는 INSERT를 시도한 뒤 그 결과로 분기한다.</b> 조회로
	 * 판정하면 동시 요청 둘이 나란히 "없음"을 보고 uq_record_active를 위반하는데, 그 예외는 트랜잭션을
	 * rollback-only로 만들어 같은 트랜잭션 안에서 되돌릴 수 없다(S15P11A705-105). Place와 같은
	 * {@code ON CONFLICT DO NOTHING} 패턴으로 충돌 자체를 없앤다.
	 *
	 * <p>이긴 요청과 진 요청 모두 같은 Record에 Context를 붙이므로, 진 요청의 본문도 사라지지 않는다.
	 */
	@Transactional
	public RecordCreateResponse create(Long memberId, RecordCreateRequest request) {
		Place place = upsertPlace(request.place());
		boolean createdNow = recordRepository.insertIfAbsent(memberId, place.getId()) == 1;
		Record record = recordRepository.findByMemberIdAndPlaceId(memberId, place.getId())
			.orElseThrow(() -> new IllegalStateException(
				"insertIfAbsent 직후 record가 조회되지 않았다: memberId=" + memberId + ", placeId=" + place.getId()));

		contextRepository.save(Context.create(record.getId(), memberId, request.contextBody()));
		if (!createdNow) {
			record.touch();
		}
		RecordSaveResult result = createdNow ? RecordSaveResult.RECORD_CREATED : RecordSaveResult.CONTEXT_ADDED;
		return RecordCreateResponse.of(result, detailOf(record, place));
	}

	@Transactional(readOnly = true)
	public RecordDetailResponse getDetail(Long memberId, Long recordId) {
		return detailOf(ownedRecord(memberId, recordId));
	}

	@Transactional(readOnly = true)
	public RecordByPlaceResponse getByKakaoPlaceId(Long memberId, String kakaoPlaceId) {
		RecordDetailResponse detail = placeRepository.findByKakaoPlaceId(kakaoPlaceId)
			.flatMap(place -> recordRepository.findByMemberIdAndPlaceId(memberId, place.getId()))
			.map(this::detailOf)
			.orElse(null);
		return new RecordByPlaceResponse(detail);
	}

	@Transactional
	public ContextMutationResponse addContext(Long memberId, Long recordId, String body) {
		Record record = ownedRecord(memberId, recordId);
		Context context = contextRepository.save(Context.create(record.getId(), memberId, body));
		record.touch();
		return ContextMutationResponse.from(context);
	}

	/**
	 * Context 교체 수정(데이터모델 6.4). 반드시 새 Context 생성이 먼저다 — 삭제를 먼저 하면
	 * 마지막 Context를 수정할 때 활성 수가 0이 되는 중간 상태가 생긴다. "마지막 Context는 삭제할 수
	 * 없다"는 삭제 유스케이스의 규칙이며 수정에는 적용하지 않는다.
	 *
	 * <p>구 Context는 소프트 삭제되므로 AI 파생 데이터도 같은 트랜잭션에서 무효화한다. 새 Context의
	 * 임베딩·Keyword는 비동기로 새로 생성되며, 늦게 도착한 구 Context의 결과는 State의
	 * {@code CANCELLED}가 막는다.
	 */
	@Transactional
	public ContextMutationResponse replaceContext(Long memberId, Long recordId, Long contextId, String body) {
		Record record = recordRepository.findByIdForUpdate(recordId)
			.orElseThrow(ResourceNotFoundException::new);
		if (!record.isOwnedBy(memberId)) {
			throw new ResourceNotFoundException();
		}
		Context old = contextRepository.findById(contextId)
			.filter(context -> context.getRecordId().equals(recordId))
			.orElseThrow(ResourceNotFoundException::new);

		Context replacement = contextRepository.saveAndFlush(Context.replacing(old, body));
		old.softDelete();
		aiDerivedDataRepository.invalidate(old.getId());
		record.touch();
		return ContextMutationResponse.from(replacement);
	}

	/**
	 * 지도 마커(API 명세 4.2). bbox 파라미터는 넷 다 주거나 모두 생략해야 한다.
	 */
	@Transactional(readOnly = true)
	public MapResponse map(Long memberId, BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng) {
		boolean allPresent = swLat != null && swLng != null && neLat != null && neLng != null;
		boolean nonePresent = swLat == null && swLng == null && neLat == null && neLng == null;
		if (!allPresent && !nonePresent) {
			throw new InvalidRequestException("bbox 파라미터(swLat·swLng·neLat·neLng)는 모두 주거나 모두 생략해야 합니다.");
		}
		List<MapMarkerResponse> items = allPresent
			? recordRepository.findMarkersWithinBounds(memberId, swLat, swLng, neLat, neLng)
			: recordRepository.findMarkers(memberId);
		return new MapResponse(boundsOf(items), items);
	}

	private Place upsertPlace(PlacePayload payload) {
		placeRepository.insertIfAbsent(
			payload.kakaoPlaceId(), payload.name(), payload.address(), payload.roadAddress(),
			payload.phone(), payload.placeUrl(), payload.lat(), payload.lng());
		return placeRepository.findByKakaoPlaceId(payload.kakaoPlaceId())
			.orElseThrow(() -> new IllegalStateException(
				"upsert 직후 place가 조회되지 않았다: " + payload.kakaoPlaceId()));
	}

	private Record ownedRecord(Long memberId, Long recordId) {
		Record record = recordRepository.findById(recordId).orElseThrow(ResourceNotFoundException::new);
		if (!record.isOwnedBy(memberId)) {
			throw new ResourceNotFoundException();
		}
		return record;
	}

	private RecordDetailResponse detailOf(Record record) {
		Place place = placeRepository.findById(record.getPlaceId())
			.orElseThrow(ResourceNotFoundException::new);
		return detailOf(record, place);
	}

	private RecordDetailResponse detailOf(Record record, Place place) {
		List<ContextResponse> contexts = contextRepository
			.findByRecordIdOrderByOriginCreatedAtAscIdAsc(record.getId())
			.stream()
			.map(ContextResponse::from)
			.toList();
		return RecordDetailResponse.of(record, place, contexts);
	}

	private BoundsResponse boundsOf(List<MapMarkerResponse> items) {
		if (items.isEmpty()) {
			return null;
		}
		BigDecimal swLat = items.stream().map(MapMarkerResponse::lat).min(Comparator.naturalOrder()).orElseThrow();
		BigDecimal neLat = items.stream().map(MapMarkerResponse::lat).max(Comparator.naturalOrder()).orElseThrow();
		BigDecimal swLng = items.stream().map(MapMarkerResponse::lng).min(Comparator.naturalOrder()).orElseThrow();
		BigDecimal neLng = items.stream().map(MapMarkerResponse::lng).max(Comparator.naturalOrder()).orElseThrow();
		return new BoundsResponse(swLat, swLng, neLat, neLng);
	}
}
