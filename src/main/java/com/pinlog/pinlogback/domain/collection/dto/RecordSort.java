package com.pinlog.pinlogback.domain.collection.dto;

/**
 * Collection 내부 Record 정렬 방향(명세 7.3, BD-46). 기준은 담은 시각
 * ({@code collection_record.created_at})이다 — Record의 시각이 아니라서 이름이 ADDED다.
 * 성격은 {@link CollectionSort}와 같다: 프론트가 상수로 고정해 보내는 값.
 */
public enum RecordSort {

	ADDED_AT_ASC,
	ADDED_AT_DESC;

	public boolean ascending() {
		return this == ADDED_AT_ASC;
	}
}
