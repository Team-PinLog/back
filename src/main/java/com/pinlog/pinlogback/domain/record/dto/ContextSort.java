package com.pinlog.pinlogback.domain.record.dto;

/**
 * Record 상세의 Context 정렬 방향(명세 5.2, BD-46). 기준은 최초 작성 시각
 * ({@code origin_created_at}, BD-25)이고 방향만 열린다. Record 상세에만 적용되며
 * Collection 상세 안의 {@code records[].contexts}는 항상 오름차순 고정이다.
 */
public enum ContextSort {

	CREATED_AT_ASC,
	CREATED_AT_DESC;

	public boolean ascending() {
		return this == CREATED_AT_ASC;
	}
}
