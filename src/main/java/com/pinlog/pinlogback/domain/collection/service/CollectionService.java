package com.pinlog.pinlogback.domain.collection.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.ai.repository.ContextKeywordRepository;
import com.pinlog.pinlogback.domain.collection.dto.CollectionAddRecordsRequest;
import com.pinlog.pinlogback.domain.collection.dto.CollectionCreateRequest;
import com.pinlog.pinlogback.domain.collection.dto.CollectionDetailResponse;
import com.pinlog.pinlogback.domain.collection.dto.CollectionSort;
import com.pinlog.pinlogback.domain.collection.dto.CollectionSummaryResponse;
import com.pinlog.pinlogback.domain.collection.dto.CollectionUpdateRequest;
import com.pinlog.pinlogback.domain.collection.dto.FollowStatusResponse;
import com.pinlog.pinlogback.domain.collection.dto.PublicCollectionDetailResponse;
import com.pinlog.pinlogback.domain.collection.dto.PublicRecordCardResponse;
import com.pinlog.pinlogback.domain.collection.dto.RecordCollectionCardResponse;
import com.pinlog.pinlogback.domain.collection.dto.RecordSort;
import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.entity.CollectionRecord;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRecordRepository;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.follow.repository.FollowRepository;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.dto.ContextResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordDetailResponse;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.global.exception.DeleteConfirmationRequiredException;
import com.pinlog.pinlogback.global.exception.ResourceNotFoundException;
import com.pinlog.pinlogback.global.response.Cursor;
import com.pinlog.pinlogback.global.response.CursorPage;
import com.pinlog.pinlogback.global.response.ErrorResponse;

/**
 * Collection 유스케이스. 활성 Collection에는 활성 연결이 최소 1개 있어야 하고(BD-11),
 * record_count는 연결 변형과 동일 트랜잭션에서 갱신한다(데이터모델 2.6).
 */
@Service
public class CollectionService {

	/** 명세 7.3 — recordSize 기본값. 펼친 책 화면이 페이지당 Record 1개를 기본으로 한다. */
	private static final int DEFAULT_RECORD_SIZE = 1;

	private final CollectionRepository collectionRepository;
	private final CollectionRecordRepository collectionRecordRepository;
	private final RecordRepository recordRepository;
	private final PlaceRepository placeRepository;
	private final ContextRepository contextRepository;
	private final MemberRepository memberRepository;
	private final FollowRepository followRepository;
	private final ContextKeywordRepository contextKeywordRepository;

	public CollectionService(CollectionRepository collectionRepository,
		CollectionRecordRepository collectionRecordRepository, RecordRepository recordRepository,
		PlaceRepository placeRepository, ContextRepository contextRepository,
		MemberRepository memberRepository, FollowRepository followRepository,
		ContextKeywordRepository contextKeywordRepository) {
		this.collectionRepository = collectionRepository;
		this.collectionRecordRepository = collectionRecordRepository;
		this.recordRepository = recordRepository;
		this.placeRepository = placeRepository;
		this.contextRepository = contextRepository;
		this.memberRepository = memberRepository;
		this.followRepository = followRepository;
		this.contextKeywordRepository = contextKeywordRepository;
	}

	@Transactional
	public CollectionSummaryResponse create(Long memberId, CollectionCreateRequest request) {
		List<Long> recordIds = request.recordIds().stream().distinct().toList();
		requireAllOwnedActiveRecords(memberId, recordIds);

		Collection collection = collectionRepository.save(Collection.create(memberId, request.title()));
		recordIds.forEach(recordId ->
			collectionRecordRepository.save(CollectionRecord.create(collection.getId(), recordId)));
		collection.increaseRecordCount(recordIds.size());
		return CollectionSummaryResponse.from(collection);
	}

	@Transactional(readOnly = true)
	public CursorPage<CollectionSummaryResponse> listMine(Long memberId, String cursor, Integer size,
		CollectionSort sort) {
		int pageSize = CursorPage.normalizeSize(size);
		Pageable probe = PageRequest.of(0, pageSize + 1);
		List<Collection> rows;
		if (cursor == null || cursor.isBlank()) {
			rows = sort.ascending()
				? collectionRepository.findFirstPageByMemberIdAsc(memberId, probe)
				: collectionRepository.findFirstPageByMemberIdDesc(memberId, probe);
		} else {
			Cursor decoded = Cursor.decode(cursor);
			rows = sort.ascending()
				? collectionRepository.findPageByMemberIdAfterAsc(
					memberId, decoded.sortKeyAsInstant(), decoded.id(), probe)
				: collectionRepository.findPageByMemberIdAfterDesc(
					memberId, decoded.sortKeyAsInstant(), decoded.id(), probe);
		}
		boolean hasNext = rows.size() > pageSize;
		List<Collection> page = hasNext ? rows.subList(0, pageSize) : rows;
		List<CollectionSummaryResponse> items = page.stream()
			.map(CollectionSummaryResponse::from)
			.toList();
		if (!hasNext) {
			return CursorPage.last(items);
		}
		Collection last = page.get(page.size() - 1);
		return CursorPage.of(items, Cursor.encode(last.getCreatedAt(), last.getId()));
	}

	/**
	 * 특정 Record가 담긴 내 Collection 목록(명세 5.10). 경로는 Record 하위지만 반환 자원도 소유
	 * 규칙도 Collection이라 유스케이스가 여기에 있다.
	 *
	 * <p>소유 확인을 생략하고 {@code memberId} 필터만으로 끝내지 않는다. 그러면 남의 Record id에
	 * 빈 200이 돌아가 "담긴 Collection이 없다"와 "볼 수 없는 Record다"가 같은 응답이 된다.
	 */
	@Transactional(readOnly = true)
	public CursorPage<RecordCollectionCardResponse> listByRecord(Long memberId, Long recordId,
		String cursor, Integer size, CollectionSort sort) {
		requireOwnedActiveRecord(memberId, recordId);
		int pageSize = CursorPage.normalizeSize(size);
		Pageable probe = PageRequest.of(0, pageSize + 1);

		List<Collection> rows;
		if (cursor == null || cursor.isBlank()) {
			rows = sort.ascending()
				? collectionRepository.findFirstPageByMemberIdAndRecordIdAsc(memberId, recordId, probe)
				: collectionRepository.findFirstPageByMemberIdAndRecordIdDesc(memberId, recordId, probe);
		} else {
			Cursor decoded = Cursor.decode(cursor);
			rows = sort.ascending()
				? collectionRepository.findPageByMemberIdAndRecordIdAfterAsc(
					memberId, recordId, decoded.sortKeyAsInstant(), decoded.id(), probe)
				: collectionRepository.findPageByMemberIdAndRecordIdAfterDesc(
					memberId, recordId, decoded.sortKeyAsInstant(), decoded.id(), probe);
		}

		boolean hasNext = rows.size() > pageSize;
		List<Collection> page = hasNext ? rows.subList(0, pageSize) : rows;
		List<RecordCollectionCardResponse> items = toRecordCollectionCards(page);
		if (!hasNext) {
			return CursorPage.last(items);
		}
		Collection last = page.get(page.size() - 1);
		return CursorPage.of(items, Cursor.encode(last.getCreatedAt(), last.getId()));
	}

	/**
	 * 페이지 전체의 Keyword를 <b>한 번에</b> 읽는다 — Collection마다 반복 조회하면 그대로
	 * N+1이다(BD-18). 공개 범위 집계를 쓴다: 내 책의 표지도 남이 보는 표지와 같은 글자여야 한다.
	 */
	private List<RecordCollectionCardResponse> toRecordCollectionCards(List<Collection> page) {
		List<Long> collectionIds = page.stream().map(Collection::getId).toList();
		Map<Long, List<String>> keywords =
			contextKeywordRepository.findCollectionKeywordsPublic(collectionIds);
		return page.stream()
			.map(collection -> RecordCollectionCardResponse.of(
				collection, keywords.getOrDefault(collection.getId(), List.of())))
			.toList();
	}

	/** {@code requireAllOwnedActiveRecords}와 같은 판정의 단건 형태다. */
	private void requireOwnedActiveRecord(Long memberId, Long recordId) {
		if (recordRepository.findByIdInAndMemberId(List.of(recordId), memberId).isEmpty()) {
			throw new ResourceNotFoundException();
		}
	}

	/**
	 * Collection 상세 통합 조회(API 명세 7.3). 소유 여부를 서버가 판별해 소유자용·공개용
	 * <b>서로 다른 DTO</b>를 반환한다(BD-13 — 상속·조건부 직렬화 금지).
	 */
	@Transactional(readOnly = true)
	public Object getDetail(Long viewerMemberId, Long collectionId, String recordCursor, Integer recordSize,
		RecordSort recordSort) {
		Collection collection = collectionRepository.findById(collectionId)
			.orElseThrow(ResourceNotFoundException::new);
		if (collection.isOwnedBy(viewerMemberId)) {
			return getDetailForOwner(viewerMemberId, collectionId, recordCursor, recordSize, recordSort);
		}
		return getDetailPublic(viewerMemberId, collection, recordCursor, recordSize, recordSort);
	}

	@Transactional(readOnly = true)
	public CollectionDetailResponse getDetailForOwner(Long memberId, Long collectionId,
		String recordCursor, Integer recordSize, RecordSort recordSort) {
		Collection collection = ownedCollection(memberId, collectionId);
		return CollectionDetailResponse.forOwner(
			collection, recordPageForOwner(memberId, collectionId, recordCursor, recordSize, recordSort));
	}

	/**
	 * 타인 공개 조회(데이터모델 5.2·5.5). 진입 검사는 발행 여부·활성·소유자 미탈퇴이고, 실패는
	 * 존재를 노출하지 않는 404다. 조립 경로가 ContextRepository를 호출하지 않으므로 Context
	 * 원문은 구조적으로 나갈 수 없다.
	 */
	private PublicCollectionDetailResponse getDetailPublic(Long viewerMemberId, Collection collection,
		String recordCursor, Integer recordSize, RecordSort recordSort) {
		if (!collection.isPublished()) {
			throw new ResourceNotFoundException();
		}
		if (!memberRepository.isActive(collection.getMemberId())) {
			throw new ResourceNotFoundException();
		}
		FollowStatusResponse follow = followRepository
			.findByFolloweeMemberIdAndFollowerMemberId(collection.getMemberId(), viewerMemberId)
			.map(found -> new FollowStatusResponse(true, found.getId(), found.getDisplayName()))
			.orElseGet(() -> new FollowStatusResponse(false, null, null));
		return PublicCollectionDetailResponse.of(
			collection, follow, recordPagePublic(collection.getId(), recordCursor, recordSize, recordSort));
	}

	/**
	 * 제목·표지 수정(API 명세 7.4). null 필드는 기존 값을 유지한다 — 최소 하나는 왔다는 것을
	 * 요청 DTO 검증이 보장한다. 표지 제거는 MVP에서 제공하지 않는다.
	 */
	@Transactional
	public CollectionSummaryResponse update(Long memberId, Long collectionId,
		CollectionUpdateRequest request) {
		Collection collection = ownedCollection(memberId, collectionId);
		if (request.title() != null) {
			collection.rename(request.title());
		}
		if (request.coverImageUrl() != null) {
			collection.changeCover(request.coverImageUrl());
		}
		return CollectionSummaryResponse.from(collection);
	}

	/**
	 * Record 추가(API 명세 7.5). 이미 담긴 Record가 섞여 있으면 실패시키지 않고 중복만 건너뛰고
	 * 나머지를 담는다(멱등). record_count 갱신과 활성 연결 판단이 경합하므로 Collection을 잠근다.
	 */
	@Transactional
	public CollectionSummaryResponse addRecords(Long memberId, Long collectionId,
		CollectionAddRecordsRequest request) {
		Collection collection = collectionRepository.findByIdForUpdate(collectionId)
			.orElseThrow(ResourceNotFoundException::new);
		if (!collection.isOwnedBy(memberId)) {
			throw new ResourceNotFoundException();
		}
		List<Long> recordIds = request.recordIds().stream().distinct().toList();
		requireAllOwnedActiveRecords(memberId, recordIds);

		Set<Long> alreadyLinked = collectionRecordRepository
			.findByCollectionIdAndRecordIdIn(collectionId, recordIds)
			.stream()
			.map(CollectionRecord::getRecordId)
			.collect(Collectors.toSet());
		List<Long> toAdd = recordIds.stream()
			.filter(recordId -> !alreadyLinked.contains(recordId))
			.toList();

		toAdd.forEach(recordId ->
			collectionRecordRepository.save(CollectionRecord.create(collectionId, recordId)));
		collection.increaseRecordCount(toAdd.size());
		return CollectionSummaryResponse.from(collection);
	}

	/**
	 * Collection에서 Record 제거(데이터모델 6.7). 빈 Collection을 허용하지 않으므로 마지막 연결은
	 * 이 API로 제거할 수 없다 — 409로 거절하고, 프론트는 확인 후 Collection 삭제 API를 호출한다.
	 * 어느 경우에도 원본 Record는 유지된다(연결만 끊긴다).
	 */
	@Transactional
	public void removeRecord(Long memberId, Long collectionId, Long recordId) {
		Collection collection = collectionRepository.findByIdForUpdate(collectionId)
			.orElseThrow(ResourceNotFoundException::new);
		if (!collection.isOwnedBy(memberId)) {
			throw new ResourceNotFoundException();
		}
		CollectionRecord link = collectionRecordRepository
			.findByCollectionIdAndRecordIdIn(collectionId, List.of(recordId))
			.stream()
			.findFirst()
			.orElseThrow(ResourceNotFoundException::new);

		if (collectionRecordRepository.countByCollectionId(collectionId) <= 1) {
			throw new DeleteConfirmationRequiredException(
				"컬렉션의 마지막 기록입니다. 제거하면 컬렉션도 함께 사라집니다.",
				new ErrorResponse.Impact(false, List.of(collectionId)));
		}
		link.softDelete();
		collection.decreaseRecordCount(1);
	}

	/**
	 * Collection 삭제(데이터모델 6.8). 연결만 함께 소프트 삭제하고 원본 Record는 유지한다.
	 */
	@Transactional
	public void deleteCollection(Long memberId, Long collectionId) {
		Collection collection = collectionRepository.findByIdForUpdate(collectionId)
			.orElseThrow(ResourceNotFoundException::new);
		if (!collection.isOwnedBy(memberId)) {
			throw new ResourceNotFoundException();
		}
		collectionRecordRepository.findByCollectionId(collectionId)
			.forEach(link -> link.softDelete());
		collection.softDelete();
	}

	private void requireAllOwnedActiveRecords(Long memberId, List<Long> recordIds) {
		List<Record> owned = recordRepository.findByIdInAndMemberId(recordIds, memberId);
		if (owned.size() != recordIds.size()) {
			throw new ResourceNotFoundException();
		}
	}

	private Collection ownedCollection(Long memberId, Long collectionId) {
		Collection collection = collectionRepository.findById(collectionId)
			.orElseThrow(ResourceNotFoundException::new);
		if (!collection.isOwnedBy(memberId)) {
			// 공개 범위 조회(타인용 응답)는 S15P11A705-71에서 붙는다. 그 전까지는 404로 은닉한다.
			throw new ResourceNotFoundException();
		}
		return collection;
	}

	private CursorPage<RecordDetailResponse> recordPageForOwner(Long memberId, Long collectionId,
		String recordCursor, Integer recordSize, RecordSort recordSort) {
		LinkPage linkPage = linkPage(collectionId, recordCursor, recordSize, recordSort);
		List<RecordDetailResponse> items = toRecordDetailsForOwner(memberId, linkPage.links());
		return linkPage.toCursorPage(items);
	}

	private CursorPage<PublicRecordCardResponse> recordPagePublic(Long collectionId, String recordCursor,
		Integer recordSize, RecordSort recordSort) {
		LinkPage linkPage = linkPage(collectionId, recordCursor, recordSize, recordSort);
		List<PublicRecordCardResponse> items = toRecordCardsPublic(linkPage.links());
		return linkPage.toCursorPage(items);
	}

	private LinkPage linkPage(Long collectionId, String recordCursor, Integer recordSize, RecordSort recordSort) {
		int pageSize = normalizeRecordSize(recordSize);
		Pageable probe = PageRequest.of(0, pageSize + 1);
		List<CollectionRecord> rows;
		if (recordCursor == null || recordCursor.isBlank()) {
			rows = recordSort.ascending()
				? collectionRecordRepository.findFirstPageByCollectionIdAsc(collectionId, probe)
				: collectionRecordRepository.findFirstPageByCollectionIdDesc(collectionId, probe);
		} else {
			Cursor decoded = Cursor.decode(recordCursor);
			rows = recordSort.ascending()
				? collectionRecordRepository.findPageByCollectionIdAfterAsc(
					collectionId, decoded.sortKeyAsInstant(), decoded.id(), probe)
				: collectionRecordRepository.findPageByCollectionIdAfterDesc(
					collectionId, decoded.sortKeyAsInstant(), decoded.id(), probe);
		}
		boolean hasNext = rows.size() > pageSize;
		List<CollectionRecord> page = hasNext ? rows.subList(0, pageSize) : rows;
		return new LinkPage(page, hasNext);
	}

	private record LinkPage(List<CollectionRecord> links, boolean hasNext) {

		<T> CursorPage<T> toCursorPage(List<T> items) {
			if (!hasNext) {
				return CursorPage.last(items);
			}
			CollectionRecord last = links.get(links.size() - 1);
			return CursorPage.of(items, Cursor.encode(last.getCreatedAt(), last.getId()));
		}
	}

	private int normalizeRecordSize(Integer requested) {
		if (requested == null || requested <= 0) {
			return DEFAULT_RECORD_SIZE;
		}
		return Math.min(requested, CursorPage.MAX_SIZE);
	}

	private List<RecordDetailResponse> toRecordDetailsForOwner(Long memberId, List<CollectionRecord> links) {
		List<Long> recordIds = links.stream().map(CollectionRecord::getRecordId).toList();
		Map<Long, Record> records = activeRecordsById(recordIds);
		Map<Long, Place> places = placesOf(records);
		Map<Long, List<Context>> contextsByRecord = recordIds.isEmpty()
			? Map.of()
			: contextRepository.findByRecordIdInOrderByOriginCreatedAtAscIdAsc(recordIds).stream()
				.collect(Collectors.groupingBy(Context::getRecordId));
		// 페이지 전체를 한 번에 — Record별 반복 조회는 그대로 N+1이다(BD-18).
		Map<Long, List<String>> keywordsByRecord =
			contextKeywordRepository.findKeywordsForOwner(recordIds, memberId);

		return links.stream()
			.filter(link -> records.containsKey(link.getRecordId()))
			.map(link -> {
				Record record = records.get(link.getRecordId());
				List<ContextResponse> contexts = contextsByRecord
					.getOrDefault(record.getId(), List.of())
					.stream()
					.map(ContextResponse::from)
					.toList();
				return RecordDetailResponse.of(
					record, places.get(record.getPlaceId()), contexts,
					keywordsByRecord.getOrDefault(record.getId(), List.of()), link.getCreatedAt());
			})
			.toList();
	}

	/**
	 * 공개 조립 경로(데이터모델 5.5). ContextRepository를 호출하지 않는다 — Context 원문이
	 * 이 경로로 나갈 방법이 없다. 소프트 삭제된 Record는 조회 자체에서 걸러진다(@SQLRestriction).
	 */
	private List<PublicRecordCardResponse> toRecordCardsPublic(List<CollectionRecord> links) {
		List<Long> recordIds = links.stream().map(CollectionRecord::getRecordId).toList();
		Map<Long, Record> records = activeRecordsById(recordIds);
		Map<Long, Place> places = placesOf(records);
		// 타인 카드이므로 공개 범위(PUBLIC만) 집계를 쓴다 — 소유자용과 메서드가 분리되어 있어
		// 잘못 고르면 컴파일이 아니라 리뷰에서 걸리는 지점이라, 이름(Public)이 경계 표식이다.
		Map<Long, List<String>> keywordsByRecord = contextKeywordRepository.findKeywordsPublic(recordIds);

		return links.stream()
			.filter(link -> records.containsKey(link.getRecordId()))
			.map(link -> {
				Record record = records.get(link.getRecordId());
				return PublicRecordCardResponse.of(
					record, places.get(record.getPlaceId()),
					keywordsByRecord.getOrDefault(record.getId(), List.of()), link.getCreatedAt());
			})
			.toList();
	}

	private Map<Long, Record> activeRecordsById(List<Long> recordIds) {
		return recordRepository.findAllById(recordIds).stream()
			.collect(Collectors.toMap(Record::getId, Function.identity()));
	}

	private Map<Long, Place> placesOf(Map<Long, Record> records) {
		return placeRepository.findAllById(
				records.values().stream().map(Record::getPlaceId).toList()).stream()
			.collect(Collectors.toMap(Place::getId, Function.identity()));
	}
}
