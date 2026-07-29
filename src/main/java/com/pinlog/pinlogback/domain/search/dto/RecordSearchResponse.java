package com.pinlog.pinlogback.domain.search.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.pinlog.pinlogback.global.response.BoundsResponse;

/**
 * {@code POST /v1/search/records} 응답(API 명세 6.1).
 *
 * <p><b>커서가 없다.</b> 검색은 페이지네이션하지 않고 유사도 상위 {@code size}개를 단일 응답으로
 * 내려준다 — 유사도 정렬은 커서 기준이 불안정하고, 하위 결과는 노출 가치가 낮다(명세 6.1).
 * 그래서 {@code CursorPage}를 쓰지 않는다.
 *
 * @param bounds 결과 Record들의 Place 전체를 감싸는 최소 사각형. 4.2와 <b>같은 규칙</b>이며 결과가
 *     없으면 명시적 {@code null}이다. 프론트가 {@code fitBounds}에 그대로 쓴다
 * @param items 유사도 내림차순. FastAPI가 준 순서를 Core 재검증 뒤에도 유지한다
 */
public record RecordSearchResponse(
	@Nullable BoundsResponse bounds,
	List<RecordSearchItemResponse> items
) {
}
