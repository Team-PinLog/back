package com.pinlog.pinlogback.domain.feed.service;

import java.util.Map;

/**
 * 요청자의 관심 Profile(feed-recommendation 3.1). 점수 계산의 입력일 뿐 응답 DTO가 아니다.
 *
 * <p>{@code keywordWeights}에는 본인의 {@code PUBLIC} + {@code PRIVATE_ONLY} Keyword가 들어간다.
 * <b>이 비대칭이 의도된 설계다</b>(feed-scoring 3.2) — 타인 Collection 특징에는 {@code PUBLIC}만
 * 들어가므로, {@code PRIVATE_ONLY}가 추천 결과를 통해 타인에게 드러날 경로가 없다.
 * {@code BLOCKED}는 양쪽 모두에서 제외한다.
 *
 * @param keywordWeights Keyword code별 정규화 가중치(합 1). 비어 있을 수 있다
 * @param recordCount 활성 Record 수. Cold Start 판정에만 쓴다
 */
public record FeedProfile(Map<String, Double> keywordWeights, int recordCount) {

	public FeedProfile {
		keywordWeights = Map.copyOf(keywordWeights);
	}

	/** Profile 계산이 실패했을 때의 값. Cold Start 경로로 폴백한다(feed-recommendation 5장). */
	public static FeedProfile empty() {
		return new FeedProfile(Map.of(), 0);
	}

	/**
	 * Record가 없는 신규 가입자뿐 아니라, Record는 있지만 AI Keyword가 아직 하나도 완료되지 않은
	 * 사용자도 Cold Start다(feed-scoring 5.1). AI가 비동기이므로 가입 직후 이 상태가 정상이다.
	 */
	public boolean isColdStart(int threshold) {
		return recordCount < threshold || keywordWeights.isEmpty();
	}
}
