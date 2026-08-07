package com.pinlog.pinlogback.domain.record.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.record.entity.Context;

public interface ContextRepository extends JpaRepository<Context, Long> {

	/**
	 * Record 상세의 Context 목록. 정렬 기준은 최초 작성 시각, 동률이면 id다
	 * (데이터모델 2.5 — 수정해도 목록 위치가 바뀌지 않는 기준). 오름차순이 기본이고
	 * {@code contextSort=CREATED_AT_DESC}일 때만 내림차순을 쓴다(BD-46). 페이지네이션이
	 * 없어 커서 부등호 짝이 필요 없고, 정렬만 뒤집으면 된다.
	 */
	List<Context> findByRecordIdOrderByOriginCreatedAtAscIdAsc(Long recordId);

	List<Context> findByRecordIdOrderByOriginCreatedAtDescIdDesc(Long recordId);

	List<Context> findByRecordIdInOrderByOriginCreatedAtAscIdAsc(List<Long> recordIds);

	/**
	 * 자연어 검색이 {@code matchedContext}를 조립할 때 쓴다. <b>{@code memberId}를 조건에 두는 것이
	 * 요점이다</b> — id 목록의 출처가 FastAPI 응답이라 남의 Context id가 섞여 들어올 수 있고, 그것을
	 * 걸러내는 것이 Spring 최종 검증이다(AI 설계 9.5). 소프트 삭제는 {@code @SQLRestriction}이 건다.
	 */
	List<Context> findByIdInAndMemberId(List<Long> ids, Long memberId);

	List<Context> findByRecordId(Long recordId);

	long countByRecordId(Long recordId);

	/**
	 * 나의 활동 기록 집계의 맥락 메모 수(S15P11A705-397).
	 *
	 * <p>{@code member_id}는 Context에 비정규화돼 있어 Record 조인 없이 센다(V3:52). 소프트 삭제는
	 * {@code @SQLRestriction("deleted_at IS NULL")}이 count 쿼리에도 걸리므로 조건을 적지 않는다 —
	 * 명시하려다 빠뜨리면 오히려 삭제분이 섞인다(S15P11A705-200에서 뮤테이션으로 확인).
	 *
	 * <p>수정으로 교체된 구 Context는 소프트 삭제되므로 세지 않는다. 즉 이 값은 "지금 살아 있는
	 * 메모 수"이지 "지금까지 쓴 메모 수"가 아니다.
	 */
	long countByMemberId(Long memberId);
}
