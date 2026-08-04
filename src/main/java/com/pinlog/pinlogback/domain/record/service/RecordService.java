package com.pinlog.pinlogback.domain.record.service;

import java.math.BigDecimal;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.pinlog.pinlogback.domain.ai.event.ContextAiRequested;
import com.pinlog.pinlogback.domain.ai.repository.AiDerivedDataRepository;
import com.pinlog.pinlogback.domain.ai.repository.ContextAiStateRepository;
import com.pinlog.pinlogback.domain.ai.repository.ContextKeywordRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.dto.ContextMutationResponse;
import com.pinlog.pinlogback.domain.record.dto.ContextResponse;
import com.pinlog.pinlogback.domain.record.dto.ContextSort;
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
import com.pinlog.pinlogback.global.response.BoundsResponse;

/**
 * Record·Context 유스케이스. 사용자 식별자는 컨트롤러가 인증 경계에서 해석한 memberId를
 * 파라미터로 받는다(BD-14) — 인증이 붙어도 이 시그니처는 바뀌지 않는다.
 */
@Service
public class RecordService {

	private final PlaceRepository placeRepository;
	private final RecordRepository recordRepository;
	private final ContextRepository contextRepository;
	private final ContextAiStateRepository contextAiStateRepository;
	private final AiDerivedDataRepository aiDerivedDataRepository;
	private final ContextKeywordRepository contextKeywordRepository;
	private final ApplicationEventPublisher events;

	public RecordService(PlaceRepository placeRepository, RecordRepository recordRepository,
		ContextRepository contextRepository, ContextAiStateRepository contextAiStateRepository,
		AiDerivedDataRepository aiDerivedDataRepository, ContextKeywordRepository contextKeywordRepository,
		ApplicationEventPublisher events) {
		this.placeRepository = placeRepository;
		this.recordRepository = recordRepository;
		this.contextRepository = contextRepository;
		this.contextAiStateRepository = contextAiStateRepository;
		this.aiDerivedDataRepository = aiDerivedDataRepository;
		this.contextKeywordRepository = contextKeywordRepository;
		this.events = events;
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

		Context context = contextRepository.save(Context.create(record.getId(), memberId, request.contextBody()));
		enqueueAiProcessing(context);
		if (!createdNow) {
			record.touch();
		}
		RecordSaveResult result = createdNow ? RecordSaveResult.RECORD_CREATED : RecordSaveResult.CONTEXT_ADDED;
		// 생성 응답의 keywords는 항상 빈 배열이다(명세 1.3) — 방금 붙인 Context는 AI 미처리 상태이고,
		// CONTEXT_ADDED로 기존 Record에 완료된 Keyword가 있어도 생성 응답에는 싣지 않는다. 조회와
		// 생성이 다른 것을 돌려주면 안 되는 값이 아니라, 생성 직후 화면이 조회를 다시 부르는 계약이다.
		return RecordCreateResponse.of(result, detailOf(record, place, List.of()));
	}

	@Transactional(readOnly = true)
	public RecordDetailResponse getDetail(Long memberId, Long recordId, ContextSort contextSort) {
		Record record = ownedRecord(memberId, recordId);
		return detailOf(record, keywordsForOwner(record, memberId), contextSort);
	}

	@Transactional(readOnly = true)
	public RecordByPlaceResponse getByKakaoPlaceId(Long memberId, String kakaoPlaceId) {
		RecordDetailResponse detail = placeRepository.findByKakaoPlaceId(kakaoPlaceId)
			.flatMap(place -> recordRepository.findByMemberIdAndPlaceId(memberId, place.getId()))
			.map(record -> detailOf(record, keywordsForOwner(record, memberId)))
			.orElse(null);
		return new RecordByPlaceResponse(detail);
	}

	@Transactional
	public ContextMutationResponse addContext(Long memberId, Long recordId, String body) {
		Record record = ownedRecord(memberId, recordId);
		Context context = contextRepository.save(Context.create(record.getId(), memberId, body));
		enqueueAiProcessing(context);
		record.touch();
		return ContextMutationResponse.from(context);
	}

	/**
	 * Context 교체 수정(데이터모델 6.4). 반드시 새 Context 생성이 먼저다 — 삭제를 먼저 하면
	 * 마지막 Context를 수정할 때 활성 수가 0이 되는 중간 상태가 생긴다. "마지막 Context는 삭제할 수
	 * 없다"는 삭제 유스케이스의 규칙이며 수정에는 적용하지 않는다.
	 *
	 * <p>AI 관점에서 이 메서드는 <b>생성 동기화 + 삭제 동기화</b>다(AI 파트 소유 명세
	 * {@code docs/ai/spec/context-state-sync.md} 5장). 여기서 붙이는 것은 신 Context의 {@code PENDING}
	 * 생성이고, 구 Context는 소프트 삭제되므로 AI 파생 데이터도 같은 트랜잭션에서 무효화한다. 구 상태를
	 * 신 Context로 승계하지 않는다 — 신 {@code context_id}는 새 처리 단위라 {@code retry_count}도 0에서
	 * 시작한다. 새 Context의 임베딩·Keyword는 비동기로 새로 생성되며, 늦게 도착한 구 Context의 결과는
	 * State의 {@code CANCELLED}가 막는다.
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
		enqueueAiProcessing(replacement);
		old.softDelete();
		aiDerivedDataRepository.invalidate(old.getId());
		record.touch();
		return ContextMutationResponse.from(replacement);
	}

	/**
	 * 지도 마커(API 명세 4.2). bbox 파라미터는 넷 다 주거나 모두 생략해야 한다. keyword는 bbox와
	 * 무관하게 조합되며, 빈 값은 400이 아니라 "필터 없음"이다 — 검색창을 지운 것이 오류일 수는 없다.
	 */
	@Transactional(readOnly = true)
	public MapResponse map(Long memberId, BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng,
		String keyword) {
		boolean allPresent = swLat != null && swLng != null && neLat != null && neLng != null;
		boolean nonePresent = swLat == null && swLng == null && neLat == null && neLng == null;
		if (!allPresent && !nonePresent) {
			throw new InvalidRequestException("bbox 파라미터(swLat·swLng·neLat·neLng)는 모두 주거나 모두 생략해야 합니다.");
		}
		String likeKeyword = toLikeKeyword(keyword);
		List<MapMarkerResponse> found = allPresent
			? recordRepository.findMarkersWithinBounds(memberId, swLat, swLng, neLat, neLng, likeKeyword)
			: recordRepository.findMarkers(memberId, likeKeyword);
		List<MapMarkerResponse> items = sortByName(found);
		return new MapResponse(
			BoundsResponse.enclosing(items, MapMarkerResponse::lat, MapMarkerResponse::lng), items);
	}

	/**
	 * 이름 오름차순, 동명이면 recordId 오름차순(API 명세 4.2). DB {@code ORDER BY}가 아니라 여기서
	 * 정렬하는 이유는 collation 의존성 때문이다 — 한글에 동순위 가중치를 주는 collation에서는
	 * {@code ORDER BY name}이 사실상 무순서라, 같은 코드가 환경(운영 DB·테스트 컨테이너)에 따라
	 * 다른 순서를 내놓는다. 응답은 회원 단위(수십~수백 행)라 애플리케이션 정렬 비용은 무시할 수준이다.
	 */
	private static List<MapMarkerResponse> sortByName(List<MapMarkerResponse> items) {
		Collator collator = Collator.getInstance(Locale.KOREAN);
		return items.stream()
			.sorted(Comparator.comparing(MapMarkerResponse::name, collator::compare)
				.thenComparing(MapMarkerResponse::recordId))
			.toList();
	}

	/**
	 * 사용자 검색어를 완성된 LIKE 패턴으로 바꾼다. 와일드카드({@code %}·{@code _})는 문자 그대로
	 * 매칭해야 하므로 이스케이프한다 — 이스케이프 문자로 백슬래시를 쓰면 JPQL 문자열 리터럴 규칙과
	 * 겹쳐 읽기 어려워서 {@code !}를 쓴다(쿼리의 {@code escape '!'}와 한 쌍).
	 *
	 * <p>소문자화를 DB의 {@code lower()}가 아니라 여기서 하는 이유는 {@link
	 * com.pinlog.pinlogback.domain.record.repository.RecordRepository#findMarkers} 주석 참조.
	 * {@code Locale.ROOT}는 실행 환경 로케일(예: 터키어 i)에 따라 결과가 달라지는 것을 막는다.
	 */
	static String toLikeKeyword(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return null;
		}
		String escaped = keyword.strip().toLowerCase(Locale.ROOT)
			.replace("!", "!!").replace("%", "!%").replace("_", "!_");
		return "%" + escaped + "%";
	}

	/**
	 * Context가 생긴 세 지점이 공유하는 AI 연동 후처리(AI 파트 소유 명세
	 * {@code docs/ai/spec/context-state-sync.md} 3장).
	 *
	 * <p><b>순서가 계약이다.</b> {@code PENDING} 행 INSERT가 FastAPI 호출보다 먼저여야 한다. 순서가
	 * 뒤집히면 워커가 상태 행을 찾지 못해 202를 받고도 아무 것도 하지 않는다. 여기서는 INSERT만
	 * 하고 호출은 커밋 이후 리스너에 맡겨 그 순서를 트랜잭션 경계로 강제한다 —
	 * {@code ContextAiRequestedListener} 참조.
	 *
	 * <p>INSERT를 Core 저장과 같은 트랜잭션에 두는 것도 같은 이유다. 떼어 내면 "Record는 저장됐는데
	 * AI State가 없어 영원히 처리되지 않는 Context"가 생긴다. {@code core}와 {@code ai}는 같은
	 * PostgreSQL 인스턴스라 이것이 단일 로컬 트랜잭션으로 성립한다.
	 *
	 * <p>트랜잭션 활성 여부를 단언하는 이유는 {@code @TransactionalEventListener}의
	 * {@code fallbackExecution} 기본값이 {@code false}이기 때문이다. 트랜잭션 없이 발행된 이벤트는
	 * <b>예외도 로그도 없이 버려진다.</b> 그러면 {@code PENDING}은 써지고 호출은 나가지 않는 상태가
	 * 조용히 만들어진다 — 이 메서드가 없애려는 바로 그 상태다. 지금은 호출 지점 셋이 모두
	 * {@code @Transactional}이지만, 그것을 관습이 아니라 구조로 붙들어 둔다.
	 */
	private void enqueueAiProcessing(Context context) {
		Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
			"AI 접수는 트랜잭션 안에서만 유효하다 — AFTER_COMMIT 리스너가 뜨지 않아 호출이 조용히 사라진다");
		contextAiStateRepository.initializePending(context.getId());
		events.publishEvent(
			new ContextAiRequested(context.getId(), context.getMemberId(), context.getRecordId()));
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

	/**
	 * {@code contextSort}는 Record 상세(5.2)에만 열린다(BD-46). 생성 응답과 by-place(5.3),
	 * Collection 상세 조립은 이 오버로드(오름차순 고정)를 그대로 쓴다.
	 */
	private RecordDetailResponse detailOf(Record record, List<String> keywords) {
		return detailOf(record, keywords, ContextSort.CREATED_AT_ASC);
	}

	private RecordDetailResponse detailOf(Record record, List<String> keywords, ContextSort contextSort) {
		Place place = placeRepository.findById(record.getPlaceId())
			.orElseThrow(ResourceNotFoundException::new);
		return detailOf(record, place, keywords, contextSort);
	}

	private RecordDetailResponse detailOf(Record record, Place place, List<String> keywords) {
		return detailOf(record, place, keywords, ContextSort.CREATED_AT_ASC);
	}

	private RecordDetailResponse detailOf(Record record, Place place, List<String> keywords,
		ContextSort contextSort) {
		List<ContextResponse> contexts = (contextSort.ascending()
			? contextRepository.findByRecordIdOrderByOriginCreatedAtAscIdAsc(record.getId())
			: contextRepository.findByRecordIdOrderByOriginCreatedAtDescIdDesc(record.getId()))
			.stream()
			.map(ContextResponse::from)
			.toList();
		return RecordDetailResponse.of(record, place, contexts, keywords);
	}

	/** 소유자 범위(PUBLIC + PRIVATE_ONLY) Keyword. 없으면 빈 목록 — AI 미완료가 오류가 아니다. */
	private List<String> keywordsForOwner(Record record, Long memberId) {
		return contextKeywordRepository
			.findKeywordsForOwner(List.of(record.getId()), memberId)
			.getOrDefault(record.getId(), List.of());
	}
}
