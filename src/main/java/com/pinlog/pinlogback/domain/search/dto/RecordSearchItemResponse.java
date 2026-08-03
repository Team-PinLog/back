package com.pinlog.pinlogback.domain.search.dto;

import java.time.Instant;
import java.util.List;

import com.pinlog.pinlogback.domain.ai.KeywordResponseStatus;

/**
 * 검색 결과 카드 한 장(API 명세 6.1). <b>단위는 Context가 아니라 Record다</b> — 유사도는 Context
 * 단위로 계산하지만 사용자에게는 Record 단위로 보인다(AI 설계 9.4).
 *
 * @param recordId 결과 Record
 * @param similarity 대표 Context의 유사도. UI 노출 여부는 프론트가 정하되 <b>항상 반환한다</b>
 *     (명세 6.1) — 정렬 근거이자 디버깅 값이다
 * @param place 장소 요약
 * @param matchedContext 그 Record에서 가장 잘 맞은 Context
 * @param keywords 매칭 Context의 Keyword가 아니라 <b>Record의 활성 Context 전체 집계</b>다(명세 6.1).
 *     {@code keyword_preset}의 {@code display_name}이며 {@code code}는 노출하지 않는다. AI가 아직
 *     처리하지 않았으면 {@code null}이 아니라 빈 배열이다(응답 조립 명세 5장)
 * @param keywordStatus 위 배열이 <b>최종인지</b>를 가른다(응답 조립 명세 5.1). 빈 배열 하나로는
 *     "처리 중"·"실패"·"판정 0건"이 구분되지 않아 화면이 셋을 같게 그렸다(back#136).
 *     <b>항상 반환하며 {@code null}이 아니다.</b> 이 필드를 읽지 않는 클라이언트는 필드가 생기기
 *     전과 완전히 같은 응답을 본다 — {@code keywords}의 의미도 값도 바뀌지 않았다
 * @param createdAt Record 생성 시각
 */
public record RecordSearchItemResponse(
	Long recordId,
	Double similarity,
	SearchPlaceResponse place,
	MatchedContextResponse matchedContext,
	List<String> keywords,
	KeywordResponseStatus keywordStatus,
	Instant createdAt
) {
}
