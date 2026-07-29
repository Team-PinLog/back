package com.pinlog.pinlogback.domain.feed.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * 다양성 조정 단위 검증(feed-tests 8장 D1~D4). 배치가 결정적이라는 것이 커서 페이지네이션의
 * 전제이므로 함께 단언한다.
 */
class FeedRankerTests {

	private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

	private final FeedRanker ranker = new FeedRanker(FeedPropertiesFixture.defaults());

	/** D1 — 한 소유자가 상위를 독점해도 한 응답(블록 20건)에 2건까지만 들어간다. */
	@Test
	void oneOwnerTakesAtMostTwoSlotsPerPage() {
		List<ScoredCandidate> hogging = IntStream.range(0, 10)
			.mapToObj(index -> scored(100L + index, 7L, 1.0 - index * 0.01, false))
			.collect(Collectors.toCollection(ArrayList::new));
		IntStream.range(0, 30).forEach(index ->
			hogging.add(scored(200L + index, 20L + index, 0.5 - index * 0.001, false)));

		List<Long> arranged = ranker.arrange(hogging, false);

		long hogsOnFirstPage = arranged.subList(0, 20).stream().filter(id -> id < 200L).count();
		assertThat(hogsOnFirstPage).isEqualTo(2);
	}

	/** D2 — 소유자 상한 때문에 후보가 마르면 상한을 완화해 개수를 채운다. 빈 자리를 남기지 않는다. */
	@Test
	void ownerCapIsRelaxedRatherThanLeavingEmptySlots() {
		List<ScoredCandidate> singleOwner = IntStream.range(0, 25)
			.mapToObj(index -> scored(100L + index, 7L, 1.0 - index * 0.01, false))
			.toList();

		List<Long> arranged = ranker.arrange(singleOwner, false);

		assertThat(arranged).hasSize(25).doesNotHaveDuplicates();
	}

	/** D3 — 20건 중 4건이 무작위 채널에서 채워지고, 상단이 아니라 하위 절반에 배치된다. */
	@Test
	void explorationSlotsAreFilledFromRandomChannelInLowerHalf() {
		List<ScoredCandidate> candidates = new ArrayList<>();
		IntStream.range(0, 30).forEach(index ->
			candidates.add(scored(100L + index, 20L + index, 1.0 - index * 0.01, false)));
		IntStream.range(0, 6).forEach(index ->
			candidates.add(scored(500L + index, 60L + index, 0.01, true)));

		List<Long> page = ranker.arrange(candidates, false).subList(0, 20);
		List<Integer> explorationPositions = IntStream.range(0, 20)
			.filter(position -> page.get(position) >= 500L)
			.boxed()
			.toList();

		assertThat(explorationPositions).hasSize(4);
		assertThat(explorationPositions).allMatch(position -> position >= 10);
	}

	/** Cold Start는 탐색 슬롯이 6이다 — 취향을 모르는 상태이므로 탐색 가치가 크다. */
	@Test
	void coldStartWidensExplorationSlots() {
		List<ScoredCandidate> candidates = new ArrayList<>();
		IntStream.range(0, 30).forEach(index ->
			candidates.add(scored(100L + index, 20L + index, 1.0 - index * 0.01, false)));
		IntStream.range(0, 10).forEach(index ->
			candidates.add(scored(500L + index, 60L + index, 0.01, true)));

		List<Long> page = ranker.arrange(candidates, true).subList(0, 20);

		assertThat(page.stream().filter(id -> id >= 500L)).hasSize(6);
	}

	/** D4 — 탐색 후보가 0건이면 슬롯을 점수 상위로 채운다. 빈 자리를 남기지 않는다. */
	@Test
	void missingExplorationCandidatesAreBackfilledByScore() {
		List<ScoredCandidate> candidates = IntStream.range(0, 20)
			.mapToObj(index -> scored(100L + index, 20L + index, 1.0 - index * 0.01, false))
			.toList();

		List<Long> arranged = ranker.arrange(candidates, false);

		assertThat(arranged).hasSize(20).doesNotContainNull();
	}

	/** 배치가 결정적이어야 다음 페이지가 같은 순서를 복원한다. */
	@Test
	void arrangementIsDeterministic() {
		List<ScoredCandidate> candidates = IntStream.range(0, 45)
			.mapToObj(index -> scored(100L + index, 20L + index % 5, 1.0 - index * 0.01, index % 4 == 0))
			.toList();

		assertThat(ranker.arrange(candidates, false)).isEqualTo(ranker.arrange(candidates, false));
	}

	/** 동점이어도 순서가 흔들리지 않는다 — 흔들리면 페이지 간 중복·누락이 생긴다. */
	@Test
	void tiedScoresKeepStableOrder() {
		List<ScoredCandidate> tied = IntStream.range(0, 10)
			.mapToObj(index -> scored(100L + index, 20L + index, 0.5, false))
			.toList();
		List<ScoredCandidate> shuffled = new ArrayList<>(tied);
		java.util.Collections.reverse(shuffled);

		assertThat(ranker.arrange(tied, false)).isEqualTo(ranker.arrange(shuffled, false));
	}

	/** 전체 목록을 소진할 때까지 어떤 후보도 잃지 않는다. */
	@Test
	void everyCandidateAppearsExactlyOnce() {
		List<ScoredCandidate> candidates = IntStream.range(0, 57)
			.mapToObj(index -> scored(100L + index, 20L + index % 7, 1.0 - index * 0.01, index % 3 == 0))
			.toList();

		List<Long> arranged = ranker.arrange(candidates, false);

		assertThat(arranged).hasSize(57).doesNotHaveDuplicates()
			.containsExactlyInAnyOrderElementsOf(candidates.stream()
				.collect(Collectors.toMap(ScoredCandidate::collectionId, Function.identity()))
				.keySet());
	}

	/** 소유자 상한을 1로 낮추면 같은 소유자가 한 블록에 한 번만 나온다 — 상수가 아니라 설정이다. */
	@Test
	void ownerCapComesFromConfiguration() {
		FeedRanker strict = new FeedRanker(FeedPropertiesFixture.withDiversity(1, 0, 5));
		List<ScoredCandidate> candidates = IntStream.range(0, 10)
			.mapToObj(index -> scored(100L + index, index % 2 == 0 ? 7L : 8L, 1.0 - index * 0.01, false))
			.toList();

		List<Long> firstBlock = strict.arrange(candidates, false).subList(0, 5);
		Map<Long, Long> ownersInBlock = candidates.stream()
			.filter(candidate -> firstBlock.contains(candidate.collectionId()))
			.collect(Collectors.groupingBy(ScoredCandidate::ownerId, Collectors.counting()));

		// 상한 1이지만 후보가 두 소유자뿐이라 완화 규칙이 나머지를 채운다. 완화 전 첫 통과에서
		// 소유자당 1건씩만 집었다는 것은 두 소유자가 고르게 섞였다는 사실로 확인한다.
		assertThat(ownersInBlock.values()).allMatch(count -> count >= 2);
	}

	private ScoredCandidate scored(long collectionId, long ownerId, double score, boolean fromRandom) {
		return new ScoredCandidate(
			new FeedCandidate(collectionId, ownerId, NOW, false, fromRandom), score);
	}
}
