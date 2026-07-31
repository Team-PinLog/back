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

	@Query("select c from Collection c where c.memberId = :memberId"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findFirstPageByMemberId(@Param("memberId") Long memberId, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId"
		+ " and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id))"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findPageByMemberIdAfter(@Param("memberId") Long memberId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId and c.isPublished = true"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findPublishedFirstPageByMemberId(@Param("memberId") Long memberId, Pageable pageable);

	@Query("select c from Collection c where c.memberId = :memberId and c.isPublished = true"
		+ " and (c.createdAt < :createdAt or (c.createdAt = :createdAt and c.id < :id))"
		+ " order by c.createdAt desc, c.id desc")
	List<Collection> findPublishedPageByMemberIdAfter(@Param("memberId") Long memberId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);
}
