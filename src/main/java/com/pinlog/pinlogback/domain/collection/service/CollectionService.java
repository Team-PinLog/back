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

import com.pinlog.pinlogback.domain.collection.dto.CollectionAddRecordsRequest;
import com.pinlog.pinlogback.domain.collection.dto.CollectionCreateRequest;
import com.pinlog.pinlogback.domain.collection.dto.CollectionDetailResponse;
import com.pinlog.pinlogback.domain.collection.dto.CollectionSummaryResponse;
import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.entity.CollectionRecord;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRecordRepository;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.dto.ContextResponse;
import com.pinlog.pinlogback.domain.record.dto.RecordDetailResponse;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.global.exception.ResourceNotFoundException;
import com.pinlog.pinlogback.global.response.Cursor;
import com.pinlog.pinlogback.global.response.CursorPage;

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

	public CollectionService(CollectionRepository collectionRepository,
		CollectionRecordRepository collectionRecordRepository, RecordRepository recordRepository,
		PlaceRepository placeRepository, ContextRepository contextRepository) {
		this.collectionRepository = collectionRepository;
		this.collectionRecordRepository = collectionRecordRepository;
		this.recordRepository = recordRepository;
		this.placeRepository = placeRepository;
		this.contextRepository = contextRepository;
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
	public CursorPage<CollectionSummaryResponse> listMine(Long memberId, String cursor, Integer size) {
		int pageSize = CursorPage.normalizeSize(size);
		Pageable probe = PageRequest.of(0, pageSize + 1);
		List<Collection> rows;
		if (cursor == null || cursor.isBlank()) {
			rows = collectionRepository.findFirstPageByMemberId(memberId, probe);
		} else {
			Cursor decoded = Cursor.decode(cursor);
			rows = collectionRepository.findPageByMemberIdAfter(
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

	@Transactional(readOnly = true)
	public CollectionDetailResponse getDetailForOwner(Long memberId, Long collectionId,
		String recordCursor, Integer recordSize) {
		Collection collection = ownedCollection(memberId, collectionId);
		return CollectionDetailResponse.forOwner(
			collection, recordPage(collectionId, recordCursor, recordSize));
	}

	@Transactional
	public CollectionSummaryResponse rename(Long memberId, Long collectionId, String title) {
		Collection collection = ownedCollection(memberId, collectionId);
		collection.rename(title);
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

	private CursorPage<RecordDetailResponse> recordPage(Long collectionId, String recordCursor,
		Integer recordSize) {
		int pageSize = normalizeRecordSize(recordSize);
		Pageable probe = PageRequest.of(0, pageSize + 1);
		List<CollectionRecord> rows;
		if (recordCursor == null || recordCursor.isBlank()) {
			rows = collectionRecordRepository.findFirstPageByCollectionId(collectionId, probe);
		} else {
			Cursor decoded = Cursor.decode(recordCursor);
			rows = collectionRecordRepository.findPageByCollectionIdAfter(
				collectionId, decoded.sortKeyAsInstant(), decoded.id(), probe);
		}
		boolean hasNext = rows.size() > pageSize;
		List<CollectionRecord> page = hasNext ? rows.subList(0, pageSize) : rows;
		List<RecordDetailResponse> items = toRecordDetails(page);
		if (!hasNext) {
			return CursorPage.last(items);
		}
		CollectionRecord last = page.get(page.size() - 1);
		return CursorPage.of(items, Cursor.encode(last.getCreatedAt(), last.getId()));
	}

	private int normalizeRecordSize(Integer requested) {
		if (requested == null || requested <= 0) {
			return DEFAULT_RECORD_SIZE;
		}
		return Math.min(requested, CursorPage.MAX_SIZE);
	}

	private List<RecordDetailResponse> toRecordDetails(List<CollectionRecord> links) {
		List<Long> recordIds = links.stream().map(CollectionRecord::getRecordId).toList();
		Map<Long, Record> records = recordRepository.findAllById(recordIds).stream()
			.collect(Collectors.toMap(Record::getId, Function.identity()));
		Map<Long, Place> places = placeRepository.findAllById(
				records.values().stream().map(Record::getPlaceId).toList()).stream()
			.collect(Collectors.toMap(Place::getId, Function.identity()));
		Map<Long, List<Context>> contextsByRecord = recordIds.isEmpty()
			? Map.of()
			: contextRepository.findByRecordIdInOrderByOriginCreatedAtAscIdAsc(recordIds).stream()
				.collect(Collectors.groupingBy(Context::getRecordId));

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
					record, places.get(record.getPlaceId()), contexts, link.getCreatedAt());
			})
			.toList();
	}
}
