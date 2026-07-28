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
}
