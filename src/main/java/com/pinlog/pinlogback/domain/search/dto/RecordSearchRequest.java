package com.pinlog.pinlogback.domain.search.dto;

import org.jspecify.annotations.Nullable;

import com.pinlog.pinlogback.global.common.InputLimits;
import com.pinlog.pinlogback.global.response.CursorPage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/search/records} 요청(API 명세 6.1).
 *
 * <p><b>검색 대상 사용자를 요청이 정하지 않는다.</b> 범위는 인증에서 해석한 memberId 하나뿐이며
 * (AI 설계 9.1) 그래서 이 DTO에는 그 자리가 없다 — 필드가 없으면 실수로 남의 범위를 넘길 수 없다.
 *
 * @param query 자연어 질의. 분해하지 않고 전체를 한 번 임베딩하므로(9.2) 곧 임베딩 입력이며,
 *     길이 상한이 그대로 호출 비용 상한이다
 * @param size 유사도 상위 몇 건을 받을지. 검색은 페이지네이션하지 않고 단일 응답이다(6.1)
 */
public record RecordSearchRequest(

	@NotBlank
	@Size(max = InputLimits.SEARCH_QUERY_MAX)
	String query,

	@Min(1)
	@Max(CursorPage.MAX_SIZE)
	@Nullable Integer size
) {

	/**
	 * 생략 시 기본값(명세 6.1). {@code @Min}과 함께 두지 않고 여기서 푸는 이유는, 기본값을 필드
	 * 기본값으로 박으면 "0을 명시적으로 보냈다"와 "안 보냈다"를 구분할 수 없기 때문이다 — 전자는
	 * 400이어야 하고 후자는 20이어야 한다.
	 */
	public int sizeOrDefault() {
		return size == null ? CursorPage.DEFAULT_SIZE : size;
	}
}
