package com.pinlog.pinlogback.domain.follow.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pinlog.pinlogback.domain.follow.entity.Follow;

public interface FollowRepository extends JpaRepository<Follow, Long> {

	Optional<Follow> findByFolloweeMemberIdAndFollowerMemberId(Long followeeMemberId, Long followerMemberId);

	/**
	 * 탈퇴 연쇄 삭제용. <b>양방향을 함께</b> 가져온다 — 내가 만든 팔로우와 나를 대상으로 하는
	 * 팔로우 모두 지워야 한다(데이터모델 6.9).
	 *
	 * <p>위 목록 조회들과 달리 {@code Member} join을 걸지 않는다. 그쪽은 "탈퇴한 상대는 숨긴다"가
	 * 목적이지만 여기서는 <b>상대의 탈퇴 여부와 무관하게</b> 내 행을 지워야 하므로, join을 걸면
	 * 상대가 이미 탈퇴한 행이 빠져 남는다.
	 */
	@Query("select f from Follow f"
		+ " where f.followerMemberId = :memberId or f.followeeMemberId = :memberId")
	List<Follow> findAllInvolving(@Param("memberId") Long memberId);

	/**
	 * 마이페이지 요약의 팔로워 수(API 명세 3.5). 유니크가
	 * {@code (followee_member_id, follower_member_id)}라 한 사람이 그 작성자의 Collection을 여러 개
	 * 팔로우해도 행이 하나이므로, 별도의 {@code DISTINCT}가 필요하지 않다.
	 *
	 * <p>상대의 탈퇴 여부를 보지 않는다 — 탈퇴는 그 회원의 follow 행을 함께 소프트 삭제하므로
	 * (S15P11A705-65) 활성 행만 세는 것으로 이미 걸러진다.
	 */
	long countByFolloweeMemberId(Long followeeMemberId);

	/** 마이페이지 요약의 팔로잉 수(API 명세 3.5). */
	long countByFollowerMemberId(Long followerMemberId);

	/**
	 * 내 팔로우 목록(Library의 팔로우 책장 축). followee가 탈퇴한 행은 제외한다 —
	 * Member entity join에 {@code @SQLRestriction}이 적용되어 탈퇴 회원이 걸러진다(데이터모델 1.4).
	 */
	@Query("select f from Follow f, Member m where m.id = f.followeeMemberId"
		+ " and f.followerMemberId = :memberId"
		+ " order by f.createdAt desc, f.id desc")
	List<Follow> findFirstPageByFollowerMemberId(@Param("memberId") Long memberId, Pageable pageable);

	@Query("select f from Follow f, Member m where m.id = f.followeeMemberId"
		+ " and f.followerMemberId = :memberId"
		+ " and (f.createdAt < :createdAt or (f.createdAt = :createdAt and f.id < :id))"
		+ " order by f.createdAt desc, f.id desc")
	List<Follow> findPageByFollowerMemberIdAfter(@Param("memberId") Long memberId,
		@Param("createdAt") Instant createdAt, @Param("id") Long id, Pageable pageable);
}
