package com.pinlog.pinlogback.domain.record.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.ai.repository.AiDerivedDataRepository;
import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.entity.CollectionRecord;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRecordRepository;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.global.exception.DeleteConfirmationRequiredException;
import com.pinlog.pinlogback.global.exception.ResourceNotFoundException;
import com.pinlog.pinlogback.global.response.ErrorResponse;

/**
 * Record·Context 삭제 유스케이스(데이터모델 6.5~6.6). 활성 개수를 세고 분기한 뒤 쓰기 때문에
 * 부모 Record 행 잠금 없이는 동시 요청에서 "활성 Context 1개 이상" 불변식이 깨진다 —
 * 서로 다른 Context를 지우는 두 트랜잭션이 공유하는 유일한 대상이 Record이므로 거기를 잠근다.
 *
 * <p>AI 파생 데이터 무효화(State CANCELLED·Embedding is_deleted)는 소프트 삭제와 <b>같은
 * 트랜잭션</b>에서 수행한다(데이터모델 6.5~6.6). 동일 PostgreSQL 인스턴스라 나눌 이유가 없고,
 * 나누면 "core는 지웠는데 검색 결과에는 계속 나오는" 부분 실패가 남는다.
 */
@Service
public class RecordDeletionService {

	private final RecordRepository recordRepository;
	private final ContextRepository contextRepository;
	private final CollectionRepository collectionRepository;
	private final CollectionRecordRepository collectionRecordRepository;
	private final AiDerivedDataRepository aiDerivedDataRepository;

	public RecordDeletionService(RecordRepository recordRepository, ContextRepository contextRepository,
		CollectionRepository collectionRepository, CollectionRecordRepository collectionRecordRepository,
		AiDerivedDataRepository aiDerivedDataRepository) {
		this.recordRepository = recordRepository;
		this.contextRepository = contextRepository;
		this.collectionRepository = collectionRepository;
		this.collectionRecordRepository = collectionRecordRepository;
		this.aiDerivedDataRepository = aiDerivedDataRepository;
	}

	/**
	 * Context 삭제(데이터모델 6.5). 마지막 Context는 이 API로 삭제할 수 없다 — 빈 Record를
	 * 허용하지 않으므로 409로 거절하고 영향 범위를 돌려준다.
	 */
	@Transactional
	public void deleteContext(Long memberId, Long recordId, Long contextId) {
		lockOwnedRecord(memberId, recordId);
		Context context = contextRepository.findById(contextId)
			.filter(found -> found.getRecordId().equals(recordId))
			.orElseThrow(ResourceNotFoundException::new);

		if (contextRepository.countByRecordId(recordId) <= 1) {
			throw new DeleteConfirmationRequiredException(
				"마지막 Context를 삭제하면 Record와 일부 Collection이 함께 삭제됩니다.",
				new ErrorResponse.Impact(true, lastCollectionIds(recordId)));
		}
		context.softDelete();
		aiDerivedDataRepository.invalidate(contextId);
	}

	/**
	 * Record 일반 삭제(데이터모델 6.6). 이 Record가 마지막인 활성 Collection이 있으면 삭제하지
	 * 않고 409로 거절한다. 프론트는 확인 후 강제 삭제를 호출한다.
	 */
	@Transactional
	public void deleteRecord(Long memberId, Long recordId) {
		Record record = lockOwnedRecord(memberId, recordId);
		List<Long> lastCollectionIds = lastCollectionIds(recordId);
		if (!lastCollectionIds.isEmpty()) {
			throw new DeleteConfirmationRequiredException(
				"이 기록을 삭제하면 일부 컬렉션이 함께 삭제됩니다.",
				new ErrorResponse.Impact(true, lastCollectionIds));
		}
		cascadeDelete(record);
	}

	/**
	 * Record 강제 삭제(데이터모델 6.6). 409 안내 후 사용자 확인을 받은 프론트가 호출하며,
	 * 마지막 Record였던 Collection까지 소프트 삭제한다. 연쇄 대상이 없어도 정상 수행한다.
	 */
	@Transactional
	public void forceDeleteRecord(Long memberId, Long recordId) {
		Record record = lockOwnedRecord(memberId, recordId);
		cascadeDelete(record);
	}

	private void cascadeDelete(Record record) {
		Long recordId = record.getId();
		List<CollectionRecord> links = collectionRecordRepository.findByRecordIdOrderByCollectionIdAsc(recordId);

		List<Context> contexts = contextRepository.findByRecordId(recordId);
		contexts.forEach(Context::softDelete);
		aiDerivedDataRepository.invalidate(contexts.stream().map(Context::getId).toList());
		for (CollectionRecord link : links) {
			// record_count 판단·갱신이 다른 요청과 경합하므로 Collection도 잠근다(6.7과 같은 구조).
			Collection collection = collectionRepository.findByIdForUpdate(link.getCollectionId())
				.orElse(null);
			boolean lastInCollection =
				collectionRecordRepository.countByCollectionId(link.getCollectionId()) <= 1;
			link.softDelete();
			if (collection == null) {
				continue;
			}
			if (lastInCollection) {
				collection.softDelete();
			} else {
				collection.decreaseRecordCount(1);
			}
		}
		record.softDelete();
	}

	private Record lockOwnedRecord(Long memberId, Long recordId) {
		Record record = recordRepository.findByIdForUpdate(recordId)
			.orElseThrow(ResourceNotFoundException::new);
		if (!record.isOwnedBy(memberId)) {
			throw new ResourceNotFoundException();
		}
		return record;
	}

	/**
	 * 이 Record가 마지막 활성 Record인 활성 Collection들 — collection_record.record_id 인덱스로
	 * 역조회한다(데이터모델 2.7).
	 */
	private List<Long> lastCollectionIds(Long recordId) {
		return collectionRecordRepository.findByRecordIdOrderByCollectionIdAsc(recordId).stream()
			.map(CollectionRecord::getCollectionId)
			.filter(collectionId -> collectionRecordRepository.countByCollectionId(collectionId) <= 1)
			.filter(collectionId -> collectionRepository.findById(collectionId).isPresent())
			.toList();
	}
}
