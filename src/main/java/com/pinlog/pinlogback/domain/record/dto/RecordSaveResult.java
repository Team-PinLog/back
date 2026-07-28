package com.pinlog.pinlogback.domain.record.dto;

/**
 * POST /records의 분기 결과(API 명세 5.1). 동일 Place에 내 활성 Record가 있으면 거절하지 않고
 * Context 추가로 처리한다(BD-12) — 서버가 판단하며 프론트에 거절을 반환하지 않는다.
 */
public enum RecordSaveResult {
	RECORD_CREATED,
	CONTEXT_ADDED
}
