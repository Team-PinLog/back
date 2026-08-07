package com.pinlog.pinlogback.domain.record.dto;

import com.pinlog.pinlogback.domain.ai.repository.TopKeywordRow;

/**
 * 지도 키워드 칩 한 개(S15P11A705-388).
 *
 * <p>{@code keywordId}를 싣는 것은 이 값이 지도 필터의 입력이기 때문이다. 표시 이름으로 거르면
 * 동명 프리셋과 표기 변경에 깨진다.
 *
 * <p>{@code recordCount}는 <b>bbox 안</b> Record 수다. 화면 밖 Record는 세지 않는다.
 */
public record TopKeywordResponse(int keywordId, String displayName, long recordCount) {

	public static TopKeywordResponse from(TopKeywordRow row) {
		return new TopKeywordResponse(row.keywordId(), row.displayName(), row.recordCount());
	}
}
