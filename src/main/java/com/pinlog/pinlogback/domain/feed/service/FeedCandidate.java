package com.pinlog.pinlogback.domain.feed.service;

import java.time.Instant;

/**
 * 후보 채널이 뽑아 온 Collection 하나(feed-recommendation 3.2). 이 단계는 <b>id와 순위에 쓰는
 * 값만</b> 다룬다 — 제목·본문 같은 표시용 데이터는 최종 선정 후 재검증 쿼리가 함께 가져온다.
 *
 * <p>채널 출처를 보존하는 것이 이 타입의 존재 이유다. 중복 제거는 개수를 줄이는 일이지만
 * 출처는 신호다 — {@code fromFollow}가 점수 공식의 {@code followSignal}이고,
 * {@code fromRandom}이 탐색 슬롯을 채울 자격이다(feed-scoring 2.3·4.2).
 *
 * @param collectionId 대상 Collection
 * @param ownerId 소유자. 다양성 조정(소유자 상한)에만 쓰고 응답에는 넣지 않는다
 * @param publishedAt 최신성 계산 기준 시각
 * @param fromFollow 팔로우 채널에서 나왔는지
 * @param fromRandom 탐색용 무작위 채널에서 나왔는지
 */
public record FeedCandidate(
	long collectionId,
	long ownerId,
	Instant publishedAt,
	boolean fromFollow,
	boolean fromRandom
) {

	public FeedCandidate mergedWith(FeedCandidate other) {
		return new FeedCandidate(
			collectionId,
			ownerId,
			publishedAt,
			fromFollow || other.fromFollow,
			fromRandom || other.fromRandom
		);
	}
}
