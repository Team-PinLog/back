package com.pinlog.pinlogback.domain.collection.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pinlog.pinlogback.domain.collection.entity.CollectionRecord;

public interface CollectionRecordRepository extends JpaRepository<CollectionRecord, Long> {

	List<CollectionRecord> findByCollectionIdAndRecordIdIn(Long collectionId, List<Long> recordIds);

	List<CollectionRecord> findByRecordId(Long recordId);

	List<CollectionRecord> findByCollectionId(Long collectionId);

	long countByCollectionId(Long collectionId);

	/**
	 * 컬렉션 내부 페이지. 정렬은 담은 순서 최신순(collection_record.created_at DESC, 데이터모델 2.7) —
	 * Record의 시각을 기준으로 삼으면 Context 수정만으로 순서가 바뀌므로 쓰지 않는다.
	 */
	@Query("select cr from CollectionRecord cr where cr.collectionId = :collectionId"
		+ " order by cr.createdAt desc, cr.id desc")
	List<CollectionRecord> findFirstPageByCollectionId(@Param("collectionId") Long collectionId,
		Pageable pageable);

	@Query("select cr from CollectionRecord cr where cr.collectionId = :collectionId"
		+ " and (cr.createdAt < :createdAt or (cr.createdAt = :createdAt and cr.id < :id))"
		+ " order by cr.createdAt desc, cr.id desc")
	List<CollectionRecord> findPageByCollectionIdAfter(@Param("collectionId") Long collectionId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);
}
