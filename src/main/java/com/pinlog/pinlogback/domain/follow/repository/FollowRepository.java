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
