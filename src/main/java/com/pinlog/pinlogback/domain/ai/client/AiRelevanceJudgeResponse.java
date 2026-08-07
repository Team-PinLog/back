package com.pinlog.pinlogback.domain.ai.client;

import java.util.List;

/** {@code POST /internal/v1/search/judge} 응답 본문. */
public record AiRelevanceJudgeResponse(List<Judgment> results) {

	public record Judgment(Long contextId, RelevanceLabel relevance) {
	}
}
