package com.pinlog.pinlogback.domain.ai.repository;

/**
 * {@link ContextKeywordRepository#findTopKeywordsInBounds}와
 * {@link ContextKeywordRepository#findTopKeywordsForOwner} 한 행.
 *
 * <p>{@code recordCount}가 {@code long}인 것은 {@code COUNT()}의 반환 타입을 그대로 받기
 * 위해서다. 회원 한 명의 Record 수라 {@code int}로도 충분하지만, 좁히는 자리를 만들면 그 자리가
 * 언젠가 조용히 넘친다.
 */
public record TopKeywordRow(int keywordId, String displayName, long recordCount) {
}
