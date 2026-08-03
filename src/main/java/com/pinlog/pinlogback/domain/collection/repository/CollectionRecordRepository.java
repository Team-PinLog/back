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

	/**
	 * Record 기준 역조회({@code ix_colrec_record}). <b>정렬은 편의가 아니라 락 순서다</b> —
	 * {@code RecordDeletionService.cascadeDelete}가 이 순서대로 Collection을 잠그므로, 서로 다른
	 * Record를 동시에 지우는 두 트랜잭션이 같은 Collection 둘을 반대 순서로 잡으면 교착이 난다.
	 *
	 * <p>정렬을 빼도 지금은 같은 순서가 나온다. 실행 계획이
	 * {@code uq_colrec_active (collection_id, record_id)}를 훑기 때문이며, 이는 쿼리가 준 보증이
	 * 아니라 계획이 우연히 준 것이다. 테이블이 커져 bitmap heap scan으로 바뀌면 물리 순서가 나온다
	 * (BI-12 정정 노트).
	 */
	List<CollectionRecord> findByRecordIdOrderByCollectionIdAsc(Long recordId);

	List<CollectionRecord> findByCollectionId(Long collectionId);

	/**
	 * 탈퇴 연쇄 삭제용. Collection이 소유자 자기 Record만 담으므로
	 * ({@code CollectionService.requireAllOwnedActiveRecords}) 이 한 번의 조회가 그 회원의 링크
	 * 전체를 덮는다 — Record 쪽에서 다시 훑을 필요가 없다(데이터모델 6.9).
	 */
	List<CollectionRecord> findByCollectionIdIn(List<Long> collectionIds);

	long countByCollectionId(Long collectionId);

	/**
	 * 컬렉션 내부 페이지. 정렬 기준은 담은 시각(collection_record.created_at, 데이터모델 2.7)이며
	 * 오래된순이 기본이고 방향은 파라미터로 열린다(BD-46) — Record의 시각을 기준으로 삼으면
	 * Context 수정만으로 순서가 바뀌므로 쓰지 않는다. 방향별 짝 규칙(order by와 커서 부등호가
	 * 함께 뒤집힌다)은 {@code CollectionRepository}와 같다.
	 */
	@Query("select cr from CollectionRecord cr where cr.collectionId = :collectionId"
		+ " order by cr.createdAt desc, cr.id desc")
	List<CollectionRecord> findFirstPageByCollectionIdDesc(@Param("collectionId") Long collectionId,
		Pageable pageable);

	@Query("select cr from CollectionRecord cr where cr.collectionId = :collectionId"
		+ " order by cr.createdAt asc, cr.id asc")
	List<CollectionRecord> findFirstPageByCollectionIdAsc(@Param("collectionId") Long collectionId,
		Pageable pageable);

	@Query("select cr from CollectionRecord cr where cr.collectionId = :collectionId"
		+ " and (cr.createdAt < :createdAt or (cr.createdAt = :createdAt and cr.id < :id))"
		+ " order by cr.createdAt desc, cr.id desc")
	List<CollectionRecord> findPageByCollectionIdAfterDesc(@Param("collectionId") Long collectionId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

	@Query("select cr from CollectionRecord cr where cr.collectionId = :collectionId"
		+ " and (cr.createdAt > :createdAt or (cr.createdAt = :createdAt and cr.id > :id))"
		+ " order by cr.createdAt asc, cr.id asc")
	List<CollectionRecord> findPageByCollectionIdAfterAsc(@Param("collectionId") Long collectionId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);
}
