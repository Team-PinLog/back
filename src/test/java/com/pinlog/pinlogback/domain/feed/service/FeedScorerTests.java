package com.pinlog.pinlogback.domain.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 점수 공식 단위 검증(feed-tests 3장 S1~S10). Spring Context도 DB도 없다 — 전 과정이 메모리 내
 * 산술이라는 것이 이 계층의 계약이기 때문이다.
 */
class FeedScorerTests {

	private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

	private final FeedScorer scorer = new FeedScorer(FeedPropertiesFixture.defaults());

	@Test
	void identicalDistributionsScoreOne() {
		Map<String, Double> distribution = Map.of("QUIET", 0.5, "WORK", 0.5);

		assertThat(FeedScorer.weightedJaccard(distribution, distribution)).isEqualTo(1.0);
	}

	@Test
	void disjointDistributionsScoreZero() {
		assertThat(FeedScorer.weightedJaccard(Map.of("QUIET", 1.0), Map.of("WORK", 1.0))).isEqualTo(0.0);
	}

	@Test
	void oneEmptyDistributionScoresZeroWithoutException() {
		assertThat(FeedScorer.weightedJaccard(Map.of("QUIET", 1.0), Map.of())).isEqualTo(0.0);
		assertThat(FeedScorer.weightedJaccard(Map.of(), Map.of("QUIET", 1.0))).isEqualTo(0.0);
	}

	@Test
	void bothEmptyDistributionsScoreZeroWithoutDivideByZero() {
		assertThat(FeedScorer.weightedJaccard(Map.of(), Map.of())).isEqualTo(0.0);
	}

	/**
	 * S5 — Keyword를 많이 가진 Collection이 무조건 유리하지 않아야 한다. 코사인 유사도를 쓰면
	 * 장소를 많이 담은 Collection이 상위를 독점한다.
	 */
	@Test
	void manyKeywordsDoNotWinByCountAlone() {
		Map<String, Double> user = Map.of("QUIET", 1.0);
		Map<String, Double> focused = Map.of("QUIET", 1.0);
		Map<String, Double> broad = Map.of("QUIET", 0.2, "WORK", 0.2, "PARTY", 0.2, "CAFE", 0.2, "SEA", 0.2);

		assertThat(FeedScorer.weightedJaccard(user, focused))
			.isGreaterThan(FeedScorer.weightedJaccard(user, broad));
	}

	@Test
	void followSignalIsBinary() {
		FeedCandidate followed = candidate(1L, 10L, NOW, true, false);
		FeedCandidate notFollowed = candidate(2L, 11L, NOW, false, false);

		List<ScoredCandidate> scored = scorer.score(List.of(followed, notFollowed),
			FeedProfile.empty(), Map.of(), Map.of(), false, NOW);

		// 다른 항은 두 후보가 같으므로 점수 차이가 곧 w_follow * 1.0이다.
		assertThat(scored.get(0).score() - scored.get(1).score()).isCloseTo(0.500, within(1e-9));
	}

	/** S7 — half-life 시점에 약 0.5, 아주 오래돼도 0이 아니다(영구 배제 금지). */
	@Test
	void recencyDecaysExponentiallyAndNeverReachesZero() {
		double fresh = recencyOf(NOW);
		double halfLife = recencyOf(NOW.minus(Duration.ofDays(14)));
		double ancient = recencyOf(NOW.minus(Duration.ofDays(3650)));

		assertThat(fresh).isCloseTo(0.125, within(1e-9));
		assertThat(halfLife / 0.125).isCloseTo(Math.exp(-1), within(1e-6));
		assertThat(ancient).isGreaterThan(0.0);
	}

	/** S8 — 노출이 cap을 넘어 쌓여도 감점이 상한에서 멈춘다. */
	@Test
	void impressionPenaltyStopsAtCap() {
		FeedCandidate seen = candidate(1L, 10L, NOW, false, false);

		double atCap = scorer.score(List.of(seen), FeedProfile.empty(), Map.of(),
			Map.of(1L, 5), false, NOW).get(0).score();
		double wayOverCap = scorer.score(List.of(seen), FeedProfile.empty(), Map.of(),
			Map.of(1L, 500), false, NOW).get(0).score();
		double unseen = scorer.score(List.of(seen), FeedProfile.empty(), Map.of(),
			Map.of(), false, NOW).get(0).score();

		assertThat(atCap).isEqualTo(wayOverCap);
		assertThat(unseen - atCap).isCloseTo(0.25, within(1e-9));
	}

	/** S9 — 각 항이 0~1로 정규화되어 있으므로 점수는 가중치 합(1.0)을 넘지 못한다. */
	@Test
	void everyTermStaysWithinNormalizedRange() {
		FeedCandidate best = candidate(1L, 10L, NOW, true, false);

		double score = scorer.score(List.of(best), new FeedProfile(Map.of("QUIET", 1.0), 10),
			Map.of(1L, Map.of("QUIET", 1.0)), Map.of(), false, NOW).get(0).score();

		assertThat(score).isCloseTo(1.0, within(1e-9));
	}

	/**
	 * S10 — 가중치가 설정에서 온다는 확인. 상수로 박혀 있으면 설정을 뒤집어도 순위가 그대로다.
	 */
	@Test
	void reversingWeightsReversesRanking() {
		FeedCandidate followedButOld = candidate(1L, 10L, NOW.minus(Duration.ofDays(60)), true, false);
		FeedCandidate freshButUnfollowed = candidate(2L, 11L, NOW, false, false);
		List<FeedCandidate> candidates = List.of(followedButOld, freshButUnfollowed);

		List<Long> byDefault = ranked(new FeedScorer(FeedPropertiesFixture.defaults()), candidates);
		List<Long> byRecencyFirst = ranked(
			new FeedScorer(FeedPropertiesFixture.withWeights(0.125, 0.375, 0.500)), candidates);

		assertThat(byDefault).containsExactly(1L, 2L);
		assertThat(byRecencyFirst).containsExactly(2L, 1L);
	}

	/** Cold Start는 keywordAffinity를 계산하지 않고 최신성 비중을 올린다(feed-scoring 5.2). */
	@Test
	void coldStartIgnoresKeywordAffinityAndFavorsRecency() {
		FeedCandidate matching = candidate(1L, 10L, NOW.minus(Duration.ofDays(30)), false, false);

		double normal = scorer.score(List.of(matching), new FeedProfile(Map.of("QUIET", 1.0), 10),
			Map.of(1L, Map.of("QUIET", 1.0)), Map.of(), false, NOW).get(0).score();
		double cold = scorer.score(List.of(matching), new FeedProfile(Map.of("QUIET", 1.0), 10),
			Map.of(1L, Map.of("QUIET", 1.0)), Map.of(), true, NOW).get(0).score();

		double decay = Math.exp(-30.0 / 14.0);
		assertThat(normal).isCloseTo(0.375 + 0.125 * decay, within(1e-9));
		assertThat(cold).isCloseTo(0.500 * decay, within(1e-9));
	}

	/** 시계 오차로 미래 시각이 들어와도 최신성 항이 1을 넘지 않는다. */
	@Test
	void futurePublishedAtDoesNotExceedNormalizedRange() {
		assertThat(recencyOf(NOW.plus(Duration.ofDays(5)))).isCloseTo(0.125, within(1e-9));
	}

	private double recencyOf(Instant publishedAt) {
		return scorer.score(List.of(candidate(1L, 10L, publishedAt, false, false)),
			FeedProfile.empty(), Map.of(), Map.of(), false, NOW).get(0).score();
	}

	private List<Long> ranked(FeedScorer target, List<FeedCandidate> candidates) {
		return target.score(candidates, FeedProfile.empty(), Map.of(), Map.of(), false, NOW).stream()
			.sorted(ScoredCandidate.ranking())
			.map(ScoredCandidate::collectionId)
			.toList();
	}

	private FeedCandidate candidate(long id, long ownerId, Instant publishedAt, boolean fromFollow,
		boolean fromRandom) {
		return new FeedCandidate(id, ownerId, publishedAt, fromFollow, fromRandom);
	}
}
