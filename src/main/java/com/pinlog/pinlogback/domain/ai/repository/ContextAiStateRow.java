package com.pinlog.pinlogback.domain.ai.repository;

/**
 * {@code ai.context_ai_state} 한 행의 재스캔 판정에 필요한 부분.
 *
 * <p>엔티티로 매핑하지 않는다 — {@code ai} 스키마의 소유는 AI 파트이고, 남의 스키마를 JPA 엔티티로
 * 고정하면 저쪽의 컬럼 추가가 우리 기동 실패({@code ddl-auto=validate})가 된다(package-structure.md).
 *
 * @param contextId 대상 Context
 * @param embeddingStatus 임베딩 단계 status
 * @param keywordStatus Keyword 단계 status. <b>두 단계는 독립 전이한다</b> — 한쪽만
 *     {@code PENDING}인 행이 정상적으로 존재한다(명세 7장)
 * @param retryCount 소진한 재시도 횟수
 */
public record ContextAiStateRow(
	long contextId,
	String embeddingStatus,
	String keywordStatus,
	int retryCount
) {
}
