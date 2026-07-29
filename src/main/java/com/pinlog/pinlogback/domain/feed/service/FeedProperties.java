package com.pinlog.pinlogback.domain.feed.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feed 추천 정책값(AI 파트 소유 명세 {@code docs/ai/spec/feed-scoring.md}).
 *
 * <p><b>모두 설정값이며 튜닝 대상이다.</b> 상수로 박으면 재배포 없이 조정할 수 없고, 가중치를
 * 바꿔도 순위가 안 바뀌는 회귀를 테스트가 잡을 수 없다(feed-tests S10). 값의 정본은 명세이고
 * 여기서 임의로 바꾸지 않는다 — 어긋나 보이면 AI 파트에 올린다.
 *
 * <p>소비자(FeedScorer·FeedRanker·FeedService)와 같은 패키지에 둔다. 설정과 그 설정을 쓰는
 * 구현이 떨어져 있으면 한쪽만 고치게 된다(package-structure.md).
 */
@ConfigurationProperties("pinlog.feed")
public record FeedProperties(
	Candidate candidate,
	Scoring scoring,
	Diversity diversity,
	ColdStart coldStart
) {

	/**
	 * 후보 채널 배분(feed-scoring 2.1). 성격이 다른 세 채널의 합집합으로 후보 풀을 만든다.
	 *
	 * @param poolSize 후보 풀 상한. 배분 합이 이보다 크면 팔로우 → 최신 → 무작위 순으로 잘라낸다
	 * @param recentLimit 최신 발행 채널 배분
	 * @param followLimit 팔로우 채널 배분
	 * @param randomLimit 탐색용 무작위 채널 배분
	 */
	public record Candidate(int poolSize, int recentLimit, int followLimit, int randomLimit) {
	}

	/**
	 * 점수 공식의 가중치와 계수(feed-scoring 3장). 세 가중치의 합은 1.0이다.
	 *
	 * @param wFollow 팔로우 채널 출처 신호의 가중치
	 * @param wKeyword {@code PUBLIC} Keyword weighted Jaccard의 가중치
	 * @param wRecency {@code published_at} 지수 감쇠의 가중치
	 * @param impressionPenalty 노출 1회당 감점
	 * @param impressionCap 감점에 반영할 노출 횟수 상한. 없으면 한 번 상위였던 항목이 영구 하위 고정된다
	 * @param impressionWindow 노출 집계 윈도우
	 * @param recencyHalfLifeDays 지수 감쇠의 half-life(일)
	 */
	public record Scoring(
		double wFollow,
		double wKeyword,
		double wRecency,
		double impressionPenalty,
		int impressionCap,
		Duration impressionWindow,
		double recencyHalfLifeDays
	) {
	}

	/**
	 * 다양성 조정(feed-scoring 4장).
	 *
	 * @param maxPerOwner 한 응답 내 동일 소유자 Collection 상한
	 * @param explorationSlots 한 응답의 탐색 슬롯 수(탐색 비중 20%)
	 * @param pageSize 슬롯 수가 비례하는 기준 페이지 크기. 응답 배치의 블록 단위이기도 하다
	 */
	public record Diversity(int maxPerOwner, int explorationSlots, int pageSize) {
	}

	/**
	 * Cold Start 판정과 대체 가중치(feed-scoring 5장).
	 *
	 * @param threshold 이 Record 수 미만이면 Cold Start
	 * @param wFollow Cold Start의 팔로우 가중치
	 * @param wKeyword Cold Start의 Keyword 가중치(0 — 입력이 없어 계산해도 전부 같은 점수가 된다)
	 * @param wRecency Cold Start의 최신성 가중치
	 * @param explorationSlots Cold Start의 탐색 슬롯 수(탐색 비중 30%)
	 */
	public record ColdStart(
		int threshold,
		double wFollow,
		double wKeyword,
		double wRecency,
		int explorationSlots
	) {
	}
}
