package com.pinlog.pinlogback.domain.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	/**
	 * 미탈퇴 회원인지. {@link Member}가 {@code @SQLRestriction("deleted_at IS NULL")}이라 행이
	 * 조회되는 것 자체가 미탈퇴 판정이다. 공개 진입 검사(데이터모델 5.5)의 "소유자 미탈퇴"를
	 * 공개 조회 경로들이 공유하는 유일한 지점이고, <b>인증 경로도 여기에 기댄다</b> —
	 * {@code JwtAuthenticationFilter}가 탈퇴 회원의 남은 Access 토큰을 이 판정으로 막는다
	 * (S15P11A705-65). <b>실패 응답은 규칙이 아니라 표현이므로
	 * 호출부가 정한다</b> — Collection 상세·팔로우는 404, 팔로우한 Shelf 목록은 빈 목록이다(BI-11·BI-13).
	 *
	 * <p>{@code existsById}가 아니라 {@code findById}를 쓰는 것은 기존 세 경로와 같은 조회를
	 * 유지하려는 것이다. 바꾸면 {@code @SQLRestriction}이 count 쿼리에도 적용되는지가 별개의
	 * 질문이 되고, 그것은 중복 제거인 이 변경의 범위가 아니다(S15P11A705-147).
	 */
	default boolean isActive(Long memberId) {
		return findById(memberId).isPresent();
	}
}
