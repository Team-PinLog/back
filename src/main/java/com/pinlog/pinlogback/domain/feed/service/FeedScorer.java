package com.pinlog.pinlogback.domain.feed.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 점수 계산(feed-scoring 3장). <b>전부 메모리 내 산술이다</b> — FastAPI·Embedding·LLM을 호출하지
 * 않고 벡터 유사도를 계산하지 않는다(feed-recommendation 2장). 같은 입력에는 항상 같은 점수가
 * 나오며, 시각 의존은 {@code now}를 인자로 받아 밖으로 밀어냈다.
 *
 * <pre>
 * score(c) =   w_follow  * followSignal(c)
 *            + w_keyword * keywordAffinity(user, c)
 *            + w_recency * recency(c)
 *            - impressionPenalty * min(impressions(user, c), cap)
 * </pre>
 */
@Component
public class FeedScorer {

	private static final double SECONDS_PER_DAY = 86_400.0;

	private final FeedProperties properties;

	public FeedScorer(FeedProperties properties) {
		this.properties = properties;
	}

	/**
	 * 후보 전체에 점수를 매긴다.
	 *
	 * @param candidates 중복 제거가 끝난 후보 풀
	 * @param profile 요청자의 관심 Profile
	 * @param collectionKeywords Collection별 {@code PUBLIC} Keyword 분포(정규화됨). 없으면 빈 Map
	 * @param impressions Collection별 최근 노출 횟수
	 * @param coldStart Cold Start 경로인지. 가중치 표가 갈린다(feed-scoring 5.2)
	 * @param now 최신성 계산 기준 시각
	 */
	public List<ScoredCandidate> score(List<FeedCandidate> candidates, FeedProfile profile,
		Map<Long, Map<String, Double>> collectionKeywords, Map<Long, Integer> impressions,
		boolean coldStart, Instant now) {
		double wFollow = coldStart ? properties.coldStart().wFollow() : properties.scoring().wFollow();
		double wKeyword = coldStart ? properties.coldStart().wKeyword() : properties.scoring().wKeyword();
		double wRecency = coldStart ? properties.coldStart().wRecency() : properties.scoring().wRecency();

		return candidates.stream()
			.map(candidate -> {
				double followSignal = candidate.fromFollow() ? 1.0 : 0.0;
				// Cold Start는 keywordAffinity를 계산하지 않는다 — 입력이 없어 모든 후보가 같은
				// 점수를 받아 무의미하다(feed-scoring 5.2).
				double affinity = wKeyword == 0.0 ? 0.0 : weightedJaccard(
					profile.keywordWeights(),
					collectionKeywords.getOrDefault(candidate.collectionId(), Map.of()));
				double recency = recency(candidate.publishedAt(), now);
				double penalty = penalty(impressions.getOrDefault(candidate.collectionId(), 0));
				double score = wFollow * followSignal + wKeyword * affinity + wRecency * recency - penalty;
				return new ScoredCandidate(candidate, score);
			})
			.toList();
	}

	/**
	 * weighted Jaccard(feed-scoring 3.2). {@code Σ min / Σ max}.
	 *
	 * <p>코사인 유사도가 아니라 이 형태를 쓰는 이유는 <b>크기 편향</b>이다 — 장소를 많이 담아
	 * Keyword가 많은 Collection이 무조건 상위에 오르면 안 된다. 집합 Jaccard와 달리 가중치를
	 * 살리므로 "아주 좋아함"과 "가끔 감"이 구분된다.
	 *
	 * <p>양쪽이 비어 분모가 0이면 0을 돌려준다. 0으로 나누지 않는다.
	 */
	public static double weightedJaccard(Map<String, Double> user, Map<String, Double> collection) {
		Set<String> keys = new HashSet<>(user.keySet());
		keys.addAll(collection.keySet());

		double intersection = 0.0;
		double union = 0.0;
		for (String key : keys) {
			double left = user.getOrDefault(key, 0.0);
			double right = collection.getOrDefault(key, 0.0);
			intersection += Math.min(left, right);
			union += Math.max(left, right);
		}
		return union == 0.0 ? 0.0 : intersection / union;
	}

	/**
	 * 지수 감쇠 {@code exp(-ageDays / halfLifeDays)}(feed-scoring 3.3). 선형 감쇠를 쓰지 않는 이유는
	 * 오래된 Collection이 0이 되어 영구히 배제되지 않게 하기 위해서다.
	 *
	 * <p>미래 시각(시계 오차)은 age 0으로 눌러 1을 넘지 않게 한다 — 각 항은 0~1로 정규화되어야
	 * 가중치 조정이 직관과 일치한다(feed-scoring 3장).
	 */
	private double recency(Instant publishedAt, Instant now) {
		double ageDays = Duration.between(publishedAt, now).toSeconds() / SECONDS_PER_DAY;
		return Math.exp(-Math.max(ageDays, 0.0) / properties.scoring().recencyHalfLifeDays());
	}

	/**
	 * 노출 패널티(feed-scoring 3.4). 상한을 두는 이유는 회복 가능성을 남기기 위해서다 — 상한이
	 * 없으면 한 번 상위에 올랐던 Collection이 노출 누적으로 영구히 하위에 고정된다.
	 */
	private double penalty(int impressions) {
		return properties.scoring().impressionPenalty()
			* Math.min(impressions, properties.scoring().impressionCap());
	}
}
