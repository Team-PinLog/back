package com.pinlog.pinlogback.domain.collection.dto;

/**
 * Collection 목록 정렬 방향(명세 7.2·9.3·8.1, BD-46). 사용자 노출 토글이 아니라 프론트가
 * 상수로 고정해 보내는 값이다 — 커서가 방향 정보를 담지 않으므로, 요청마다 방향이 변하는
 * 사용처에 쓰려면 커서 방향 검증이 선행돼야 한다(BD-46 재검토 트리거).
 *
 * <p>정의되지 않은 값은 enum 바인딩 실패로 400 {@code INVALID_INPUT}이 된다.
 */
public enum CollectionSort {

	CREATED_AT_ASC,
	CREATED_AT_DESC;

	public boolean ascending() {
		return this == CREATED_AT_ASC;
	}
}
