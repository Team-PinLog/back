package com.pinlog.pinlogback.domain.record.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.record.entity.Context;

public interface ContextRepository extends JpaRepository<Context, Long> {

	/**
	 * Record 상세의 Context 목록. 정렬은 최초 작성 시각 오름차순, 동률이면 id 오름차순이다
	 * (데이터모델 2.5 — 수정해도 목록 위치가 바뀌지 않는 기준).
	 */
	List<Context> findByRecordIdOrderByOriginCreatedAtAscIdAsc(Long recordId);

	List<Context> findByRecordIdInOrderByOriginCreatedAtAscIdAsc(List<Long> recordIds);

	/**
	 * 자연어 검색이 {@code matchedContext}를 조립할 때 쓴다. <b>{@code memberId}를 조건에 두는 것이
	 * 요점이다</b> — id 목록의 출처가 FastAPI 응답이라 남의 Context id가 섞여 들어올 수 있고, 그것을
	 * 걸러내는 것이 Spring 최종 검증이다(AI 설계 9.5). 소프트 삭제는 {@code @SQLRestriction}이 건다.
	 */
	List<Context> findByIdInAndMemberId(List<Long> ids, Long memberId);

	List<Context> findByRecordId(Long recordId);

	long countByRecordId(Long recordId);
}
