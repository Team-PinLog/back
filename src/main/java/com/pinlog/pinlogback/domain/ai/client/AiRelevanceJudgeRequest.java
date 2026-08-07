package com.pinlog.pinlogback.domain.ai.client;

import java.util.List;

/**
 * {@code POST /internal/v1/search/judge} 요청 본문. {@code candidates}는 3신호 병합까지 끝난
 * <b>최종 후보</b>다 — ai(FastAPI)는 본문을 저장하지 않으므로({@code ai.context_embedding}·
 * {@code ai.context_keyword} 어디에도 텍스트 컬럼이 없다) back이 능동적으로 본문을 실어 보낸다.
 * "FastAPI는 본문을 반환하지 않는다"(05_AI_설계 L626·L634·L933)는 ai→back 응답 방향의 조항이라
 * 이 방향(back→ai 요청)을 막지 않는다 — {@code ContextProcessRequest.text}가 이미 같은 방향의
 * 선례다.
 */
public record AiRelevanceJudgeRequest(String query, List<Candidate> candidates) {

	public record Candidate(Long contextId, String placeName, String body) {
	}
}
