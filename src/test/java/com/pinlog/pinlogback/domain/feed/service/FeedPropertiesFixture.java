package com.pinlog.pinlogback.domain.feed.service;

import java.time.Duration;

/**
 * 단위 테스트용 정책값. {@code application.yml}의 초기값과 같은 수치를 쓴다 — 여기서 다른 값을
 * 쓰면 단위 테스트가 통과해도 실제 배포 동작과 어긋난다.
 */
final class FeedPropertiesFixture {

	private FeedPropertiesFixture() {
	}

	static FeedProperties defaults() {
		return withWeights(0.500, 0.375, 0.125);
	}

	static FeedProperties withWeights(double wFollow, double wKeyword, double wRecency) {
		return new FeedProperties(
			new FeedProperties.Candidate(200, 100, 80, 20),
			new FeedProperties.Scoring(wFollow, wKeyword, wRecency, 0.05, 5, Duration.ofDays(7), 14),
			new FeedProperties.Diversity(2, 4, 20),
			new FeedProperties.ColdStart(3, 0.500, 0.000, 0.500, 6));
	}

	static FeedProperties withDiversity(int maxPerOwner, int explorationSlots, int pageSize) {
		return new FeedProperties(
			new FeedProperties.Candidate(200, 100, 80, 20),
			new FeedProperties.Scoring(0.500, 0.375, 0.125, 0.05, 5, Duration.ofDays(7), 14),
			new FeedProperties.Diversity(maxPerOwner, explorationSlots, pageSize),
			new FeedProperties.ColdStart(3, 0.500, 0.000, 0.500, 6));
	}
}
