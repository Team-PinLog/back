package com.pinlog.pinlogback.domain.feed.service;

import java.util.Comparator;

/**
 * 점수가 매겨진 후보. 정렬 기준을 이 타입이 들고 있다.
 *
 * @param candidate 원본 후보(채널 출처·소유자를 담고 있다)
 * @param score 최종 점수
 */
public record ScoredCandidate(FeedCandidate candidate, double score) {

	/**
	 * 점수 내림차순. <b>동점 처리가 이 비교자의 존재 이유다</b> — 같은 입력에 같은 순서가 나와야
	 * 커서 페이지네이션이 중복·누락 없이 이어진다. 점수만으로 정렬하면 동점 구간의 순서가
	 * 정렬 알고리즘과 입력 순서에 좌우되므로, {@code publishedAt}과 id로 끝까지 결정한다.
	 */
	public static Comparator<ScoredCandidate> ranking() {
		return Comparator.comparingDouble(ScoredCandidate::score).reversed()
			.thenComparing(scored -> scored.candidate().publishedAt(), Comparator.reverseOrder())
			.thenComparing(scored -> scored.candidate().collectionId(), Comparator.reverseOrder());
	}

	public long collectionId() {
		return candidate.collectionId();
	}

	public long ownerId() {
		return candidate.ownerId();
	}

	/** 탐색 슬롯을 채울 자격. 탐색용 무작위 채널에서 나온 후보만 해당한다(feed-scoring 4.2). */
	public boolean isExploration() {
		return candidate.fromRandom();
	}
}
