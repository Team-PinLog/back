package com.pinlog.pinlogback.domain.feed.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

/**
 * 다양성 조정(feed-scoring 4장). 점수 상위를 그대로 내보내면 같은 소유자가 몰리고, 순수
 * exploit만 하면 Profile이 자기 강화되어 새로운 취향을 발견할 수 없다.
 *
 * <p><b>블록 단위로 배치하는 것이 이 클래스의 핵심 판단이다.</b> "한 응답 내 동일 소유자 최대
 * 2건"과 "20개 중 탐색 4건"은 <b>한 응답</b>에 대한 규칙이므로, 전체 정렬 결과를
 * {@code diversity.page-size} 크기의 블록으로 끊고 블록마다 규칙을 적용한다. 전체 목록에
 * 한 번만 적용하면 두 번째 페이지부터 규칙이 사라진다.
 *
 * <p>결과는 결정적이다 — 무작위성은 후보 채널(Session seed)에만 있고 배치에는 없다. 그래야
 * 커서로 이어받은 다음 페이지가 같은 순서를 복원한다.
 */
@Component
public class FeedRanker {

	private final FeedProperties properties;

	public FeedRanker(FeedProperties properties) {
		this.properties = properties;
	}

	/**
	 * 점수 정렬 → 소유자 상한 → 탐색 슬롯 순으로 조정한 최종 순서를 돌려준다.
	 *
	 * @param scored 점수가 매겨진 후보 전체
	 * @param coldStart Cold Start면 탐색 슬롯을 늘린다(취향을 모르는 상태이므로 탐색 가치가 크다)
	 * @return Collection id의 최종 노출 순서
	 */
	public List<Long> arrange(List<ScoredCandidate> scored, boolean coldStart) {
		List<ScoredCandidate> ranked = scored.stream().sorted(ScoredCandidate.ranking()).toList();
		int blockSize = properties.diversity().pageSize();
		int slots = coldStart
			? properties.coldStart().explorationSlots()
			: properties.diversity().explorationSlots();

		Set<Long> used = new HashSet<>();
		List<Long> arranged = new ArrayList<>(ranked.size());
		while (used.size() < ranked.size()) {
			arranged.addAll(nextBlock(ranked, used, blockSize, slots));
		}
		return List.copyOf(arranged);
	}

	private List<Long> nextBlock(List<ScoredCandidate> ranked, Set<Long> used, int blockSize, int slots) {
		int remaining = ranked.size() - used.size();
		int size = Math.min(blockSize, remaining);
		Map<Long, Integer> ownerCounts = new HashMap<>();

		// 탐색 몫을 먼저 떼어 둔다. 점수 상위로 블록을 다 채운 뒤에 탐색을 넣으려 하면 자리가 없다.
		List<ScoredCandidate> exploration = take(ranked, used, ownerCounts,
			Math.min(slots, size), ScoredCandidate::isExploration);
		List<ScoredCandidate> exploit = take(ranked, used, ownerCounts,
			size - exploration.size(), candidate -> true);

		return place(exploit, exploration, size);
	}

	/**
	 * 조건에 맞는 후보를 점수 순으로 {@code limit}개 집는다. 소유자 상한에 걸린 항목은 버리지 않고
	 * 뒤로 미룬다(다음 블록에서 다시 후보가 된다).
	 *
	 * <p>상한 때문에 개수를 못 채우면 <b>상한을 무시하고 채운다</b>(feed-scoring 4.1) — 빈 자리를
	 * 남기는 것보다 낫다. 이 2단계 통과가 D2의 "상한으로 후보가 마름" 시나리오를 흡수한다.
	 */
	private List<ScoredCandidate> take(List<ScoredCandidate> ranked, Set<Long> used,
		Map<Long, Integer> ownerCounts, int limit, Predicate<ScoredCandidate> filter) {
		List<ScoredCandidate> picked = new ArrayList<>(Math.max(limit, 0));
		if (limit <= 0) {
			return picked;
		}
		int maxPerOwner = properties.diversity().maxPerOwner();
		for (boolean enforceOwnerCap : new boolean[] {true, false}) {
			for (ScoredCandidate candidate : ranked) {
				if (picked.size() >= limit) {
					return picked;
				}
				if (used.contains(candidate.collectionId()) || !filter.test(candidate)) {
					continue;
				}
				int owned = ownerCounts.getOrDefault(candidate.ownerId(), 0);
				if (enforceOwnerCap && owned >= maxPerOwner) {
					continue;
				}
				picked.add(candidate);
				used.add(candidate.collectionId());
				ownerCounts.put(candidate.ownerId(), owned + 1);
			}
		}
		return picked;
	}

	/**
	 * 탐색 항목을 블록 <b>하위 절반</b>에 분산 배치한다(feed-scoring 4.2). 상단을 무작위로 채우면
	 * 첫인상이 나빠지므로 위치를 고정하지 않되 상단에는 두지 않는다.
	 */
	private List<Long> place(List<ScoredCandidate> exploit, List<ScoredCandidate> exploration, int size) {
		Long[] block = new Long[size];
		int lowerHalfStart = (size + 1) / 2;
		int span = size - lowerHalfStart;
		for (int i = 0; i < exploration.size(); i++) {
			int offset = span <= 0 || exploration.size() >= span
				? Math.max(size - exploration.size() + i, 0)
				: lowerHalfStart + i * span / exploration.size();
			block[free(block, offset)] = exploration.get(i).collectionId();
		}
		int cursor = 0;
		for (ScoredCandidate candidate : exploit) {
			cursor = free(block, cursor);
			block[cursor] = candidate.collectionId();
		}
		return List.of(block);
	}

	/** {@code from}부터 앞으로, 없으면 처음부터 되감아 비어 있는 자리를 찾는다. */
	private int free(Long[] block, int from) {
		for (int i = from; i < block.length; i++) {
			if (block[i] == null) {
				return i;
			}
		}
		for (int i = 0; i < from; i++) {
			if (block[i] == null) {
				return i;
			}
		}
		throw new IllegalStateException("빈 자리 없이 배치를 시도했습니다.");
	}
}
