package com.pinlog.pinlogback.domain.collection.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pinlog.pinlogback.domain.collection.entity.Collection;

import jakarta.persistence.LockModeType;

public interface CollectionRepository extends JpaRepository<Collection, Long> {

	/**
	 * 탈퇴 연쇄 삭제용. 기존 목록 조회들과 달리 페이지네이션이 없다 — 커서로 나누는 것은 사용자에게
	 * 보여 줄 때 필요하고, 여기서는 그 회원의 Collection 전체가 한 트랜잭션의 대상이다(데이터모델 6.9).
	 */
	List<Collection> findByMemberId(Long memberId);

	/** 마이페이지 요약의 활성 Collection 수(API 명세 3.5). */
	long countByMemberId(Long memberId);

	/**
	 * 활성 연결 수를 세고 분기한 뒤 record_count를 쓰는 유스케이스의 행 잠금(데이터모델 6.7).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Collection c where c.id = :collectionId")
	Optional<Collection> findByIdForUpdate(@Param("collectionId") Long collectionId);

	/**
	 * 목록 커서 쿼리는 방향별로 짝을 이룬다(BD-46). <b>{@code order by}와 커서 비교 부등호가
	 * 함께 뒤집혀야 한다</b> — 내림차순은 {@code <}(더 오래된 것이 다음), 오름차순은 {@code >}
	 * (더 최신인 것이 다음)이다. 한쪽만 뒤집으면 컴파일도 단순 조회 테스트도 통과한 채 커서
	 * 페이지가 조용히 어긋나므로, 방향을 파라미터로 섞지 않고 메서드를 나눈다. 인덱스
	 * ({@code ix_collection_member}, created_at DESC)는 btree 역방향 스캔으로 양방향을 받는다.
	 */
	@Query("select c from Collection c where c.memberId = :memberId"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findFirstPageByMemberIdDesc(@Param("memberId") Long memberId, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId"
		+ " order by c.createdAt asc, c.id asc")
	List<Collection> findFirstPageByMemberIdAsc(@Param("memberId") Long memberId, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId"
		+ " and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id))"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findPageByMemberIdAfterDesc(@Param("memberId") Long memberId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId"
		+ " and (c.createdAt > :createdAt or (c.createdAt = :createdAt and c.id > :id))"
		+ " order by c.createdAt asc, c.id asc")
	List<Collection> findPageByMemberIdAfterAsc(@Param("memberId") Long memberId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId and c.isPublished = true"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findPublishedFirstPageByMemberIdDesc(@Param("memberId") Long memberId, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId and c.isPublished = true"
		+ " order by c.createdAt asc, c.id asc")
	List<Collection> findPublishedFirstPageByMemberIdAsc(@Param("memberId") Long memberId, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId and c.isPublished = true"
		+ " and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id))"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findPublishedPageByMemberIdAfterDesc(@Param("memberId") Long memberId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId and c.isPublished = true"
		+ " and (c.createdAt > :createdAt or (c.createdAt = :createdAt and c.id > :id))"
		+ " order by c.createdAt asc, c.id asc")
	List<Collection> findPublishedPageByMemberIdAfterAsc(@Param("memberId") Long memberId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

	/**
	 * 특정 Record가 담긴 내 Collection의 첫 페이지(명세 5.10). 정렬 기준은 7.2와 같은
	 * {@code collection.created_at}이라 {@code ix_collection_member}를 순서대로 탈 수 있다 —
	 * 담은 시각을 기준으로 삼으면 {@code ix_colrec_record}에 시각이 없어 매번 정렬해야 한다.
	 *
	 * <p>{@code exists} 서브쿼리에도 {@code @SQLRestriction}이 걸리므로 연결의 소프트 삭제 조건을
	 * 적지 않는다. {@code c.memberId} 조건은 잉여다(Collection은 소유자 자기 Record만 담는다).
	 * 그래도 남긴다 — 그 불변식이 깨지는 날 남의 Collection이 새는 것보다 조건 하나가 낫다.
	 */
	@Query("select c from Collection c where c.memberId = :memberId"
		+ " and exists (select 1 from CollectionRecord cr"
		+ " where cr.collectionId = c.id and cr.recordId = :recordId)"
		+ " order by c.createdAt asc, c.id asc")
	List<Collection> findFirstPageByMemberIdAndRecordIdAsc(@Param("memberId") Long memberId,
		@Param("recordId") Long recordId, Pageable pageable);

	/** {@link #findFirstPageByMemberIdAndRecordIdAsc}의 내림차순 짝(BD-46, 명세 7.2 sort=CREATED_AT_DESC). */
	@Query("select c from Collection c where c.memberId = :memberId"
		+ " and exists (select 1 from CollectionRecord cr"
		+ " where cr.collectionId = c.id and cr.recordId = :recordId)"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findFirstPageByMemberIdAndRecordIdDesc(@Param("memberId") Long memberId,
		@Param("recordId") Long recordId, Pageable pageable);
}
