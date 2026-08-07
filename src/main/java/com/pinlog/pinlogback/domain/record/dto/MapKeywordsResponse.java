package com.pinlog.pinlogback.domain.record.dto;

import java.util.List;

/**
 * {@code GET /v1/records/map/keywords} 응답(S15P11A705-388).
 *
 * <p>배열을 그대로 내보내지 않고 객체로 감싼다 — 나중에 필드를 더할 때 클라이언트를 깨지 않는다.
 * 대상이 없으면 {@code null}이 아니라 빈 배열이다.
 */
public record MapKeywordsResponse(List<TopKeywordResponse> items) {
}
