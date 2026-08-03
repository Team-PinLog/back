package com.pinlog.pinlogback.domain.feed.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordLabel;

/**
 * Feed 카드에 실을 Keyword를 고르고 정렬한다(feed-recommendation 3.7.1, P46).
 *
 * <p><b>Collection 내재 기준이다.</b> 추천 점수의 {@code keywordAffinity}는 보는 사람 기준이므로
 * (요청자 Profile과의 weighted Jaccard, feed-scoring 3.2) 여기 쓰지 않는다. 쓰면 같은 Collection이
 * 사람마다 다른 Keyword를 보여주고, {@code collection_id}로 잡힌 특징 Cache를 표시에 재사용할 수
 * 없으며, <b>남의 카드에 내 Profile이 비친다</b>.
 *
 * <p>정렬 키는 사전식으로 셋이며 셋 다 결정적이다.
 *
 * <pre>
 * 1  축 내 순위 ASC    같은 category 안에서 (빈도 DESC, preset id ASC) 로 매긴 1-based 순위
 * 2  빈도 DESC         Collection 안에서 그 Keyword가 붙은 Context 수(정규화 값)
 * 3  preset id ASC     UNIQUE 정수라 여기서 전순서가 완성된다
 * </pre>
 *
 * <p><b>축이 1순위인 것은 실측이 강제한 선택이다.</b> 시연 DB 16 Collection 중 10건(63%)이 모든
 * Keyword의 빈도가 1이고, 4개로 자를 필요가 있는 6건 가운데 4건이 거기 속한다 — 빈도만으로는 그
 * 4건에서 한 개도 고르지 못한다. 축 1순위가 라운드로빈 효과를 내어 상위 4칸이 서로 다른 축으로
 * 먼저 채워지고, 축이 4개 미만이면 남는 칸이 축 2순위 이하로 채워진다. <b>축이 하나뿐이어도 4칸이
 * 찬다</b> — 「축당 1개」로 못 박으면 4축을 다 가진 31%를 뺀 나머지가 가진 것보다 적게 나간다.
 *
 * <p>대가는 하나다. 축 분산이 1순위이므로 <b>더 높은 빈도가 뒤로 밀릴 수 있다</b> — 한 축이 빈도
 * 5·4를 가지고 다른 축이 1을 가지면 순서는 {@code [5, 1, 4]}다. 규칙의 의도이며 결함이 아니다.
 */
final class FeedKeywordSelector {

	/**
	 * 축 내 순위를 매기는 순서. 같은 축 안에서 빈도가 높은 것이 1순위를 가져가고, 빈도가 같으면
	 * {@code preset.id}가 작은 쪽이 가져간다.
	 */
	private static final Comparator<Candidate> WITHIN_AXIS =
		Comparator.comparingDouble(Candidate::weight).reversed()
			.thenComparingInt(candidate -> candidate.label().presetId());

	/**
	 * 최종 표시 순서. {@code preset.id}가 UNIQUE이므로 이 비교자는 <b>전순서</b>이며, 같은 입력에
	 * 언제나 같은 결과를 낸다 — 입력 Map의 순회 순서가 달라져도 마찬가지다(feed-tests KW5·KW10).
	 */
	private static final Comparator<Ranked> DISPLAY_ORDER =
		Comparator.comparingInt(Ranked::axisRank)
			.thenComparing(Comparator.comparingDouble((Ranked entry) -> entry.candidate().weight())
				.reversed())
			.thenComparingInt(entry -> entry.candidate().label().presetId());

	private FeedKeywordSelector() {
	}

	/**
	 * 한 Collection의 표시 Keyword.
	 *
	 * <p>거르기를 <b>자르기보다 먼저</b> 둔다. 표시값을 못 찾은 code와 표시값이 겹치는 code는 자리를
	 * 비우지 않고 다음 후보가 이어받는다 — 순서를 뒤에 두면 4개를 요청했는데 3개가 나가고, 증상이
	 * 데이터에 따라 산발적으로만 드러난다(feed-tests KW6·KW7).
	 *
	 * @param weights Keyword {@code code} → Collection 내 정규화 가중치. 정규화는 Collection마다
	 *     같은 값으로 나눈 것이라 <b>순서는 원래 빈도와 같다</b>
	 * @param labels Keyword {@code code} → 표시 정보. 키가 없는 code는 노출 대상이 아니거나 폐기된
	 *     Preset이므로 응답에서 <b>뺀다</b>. {@code code}로 대신 채우면 그 폴백이 곧 08 §6.1 위반이다
	 * @param limit 상한. 넘으면 앞에서 자른다
	 * @return 표시값. Keyword가 없으면 빈 목록이며 <b>오류가 아니다</b>(feed-recommendation 3.7)
	 */
	static List<String> select(Map<String, Double> weights, Map<String, FeedKeywordLabel> labels,
		int limit) {
		List<Candidate> candidates = new ArrayList<>(weights.size());
		weights.forEach((code, weight) -> {
			FeedKeywordLabel label = labels.get(code);
			if (label != null) {
				candidates.add(new Candidate(label, weight));
			}
		});
		candidates.sort(WITHIN_AXIS);

		Map<String, Integer> seenPerAxis = new HashMap<>();
		List<Ranked> ranked = new ArrayList<>(candidates.size());
		for (Candidate candidate : candidates) {
			int axisRank = seenPerAxis.merge(candidate.label().category(), 1, Integer::sum);
			ranked.add(new Ranked(candidate, axisRank));
		}
		ranked.sort(DISPLAY_ORDER);

		// LinkedHashSet이 표시값 중복을 흡수한다. 자르기가 그 뒤라 중복이 자리를 잡아먹지 않는다.
		LinkedHashSet<String> selected = new LinkedHashSet<>();
		for (Ranked entry : ranked) {
			if (selected.size() == limit) {
				break;
			}
			selected.add(entry.candidate().label().displayName());
		}
		return List.copyOf(selected);
	}

	private record Candidate(FeedKeywordLabel label, double weight) {
	}

	private record Ranked(Candidate candidate, int axisRank) {
	}
}
