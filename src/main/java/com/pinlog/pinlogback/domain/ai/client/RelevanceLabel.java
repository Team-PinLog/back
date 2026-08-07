package com.pinlog.pinlogback.domain.ai.client;

/**
 * 검색 후보 LLM 관련도 재판정 4단계(검색 4번째 신호). ai 레포
 * {@code app/schema/relevance.py::RelevanceLabel}과 이름·순서가 같아야 한다 — 어긋나면 등급
 * 문자열은 역직렬화되지만 back의 정렬 우선순위(가장 높은 등급부터)가 그 파트의 의도와 달라진다.
 */
public enum RelevanceLabel {
	VERY_RELEVANT,
	RELEVANT,
	WEAKLY_RELEVANT,
	NOT_RELEVANT
}
