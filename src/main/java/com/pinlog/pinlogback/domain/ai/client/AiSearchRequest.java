package com.pinlog.pinlogback.domain.ai.client;

/**
 * {@code POST /internal/v1/search} 요청 본문. 논리 계약의 정본은 공용 계약
 * {@code static/05_AI_설계.md} 9장이고, 실행 가능한 계약은 ai 레포의
 * {@code app/schema/search.py::SearchRequest}다.
 *
 * @param userId 검색 범위 필터값. Spring이 인증에서 해석한 memberId이며, FastAPI는 이것을
 *     인가 근거로 쓰지 않는다 — 인가의 원본은 Core이고 최종 판단은 Spring이 한다(AI 설계 9.5)
 * @param query 사용자가 입력한 자연어 질의. 분해하지 않고 전체를 한 번 임베딩한다(9.2)
 * @param limit 유사도 상위 몇 건을 받을지. 공개 계약의 {@code size}가 그대로 온다
 * @param embeddingProfile Spring 설정에서 읽은 값. FastAPI가 자기 설정과 대조해 다르면 422다(7.1)
 */
public record AiSearchRequest(
	Long userId,
	String query,
	Integer limit,
	String embeddingProfile
) {
}
