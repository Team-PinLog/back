package com.pinlog.pinlogback.domain.ai.client;

import java.util.List;

/**
 * {@code POST /internal/v1/search} 응답 본문(ai 레포 {@code app/schema/search.py::SearchResponse}).
 *
 * <p><b>본문은 오지 않는다.</b> FastAPI는 Record별 최고 유사도 Context의 {@code contextId}까지만
 * 돌려주고 Context 본문은 Spring이 Core에서 조회한다(AI 설계 9.3·9.4). {@code ai} 스키마에 본문
 * 사본을 두지 않는다는 뜻이기도 하다.
 *
 * @param results 유사도 내림차순. Record 단위로 이미 집계된 목록이다
 */
public record AiSearchResponse(List<Match> results) {

	/**
	 * @param recordId 매칭된 Record. {@code ai} 스키마의 비정규화 값이므로 Spring이 재검증한다
	 * @param contextId 그 Record에서 가장 유사한 Context. {@code matchedContext}의 근거다
	 * @param similarity {@code 1 - cosine distance}. 정렬 근거이자 응답 필드다(API 명세 6.1)
	 * @param keywordMatched 키워드 재정렬(ai 레포 {@code SearchService._rerank_by_keyword})이 이미
	 *     계산하는 Preset 매치 여부(ai 레포 {@code S15P11A705-399}). 결합 신뢰도 게이트(S15P11A705-400)의
	 *     S3 신호로 쓴다 — 결과를 보여줄지 정하는 데만 쓰고 응답에는 노출하지 않는다. 계약상 항상
	 *     오지만, 누락·{@code null}은 신호 없음(false)으로 본다({@link RecordSearchService}의 다른
	 *     null 방어와 같은 원칙 — 상대 응답의 결함이 우리 500으로 나타나면 안 된다)
	 */
	public record Match(Long recordId, Long contextId, Double similarity, Boolean keywordMatched) {
	}
}
