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
 * <p><b>정렬 키는 사전식으로 셋이며, 출처가 서로 다르다.</b>
 *
 * <pre>
 * 1  빈도 DESC        Collection 안에서 그 Keyword가 붙은 Context 수   프론트와 구두 합의 (2026-08-03)
 * 2  축 내 순위 ASC    같은 빈도·같은 category 안에서 preset id 순위    동점 규칙 — 우리 판단
 * 3  preset id ASC    UNIQUE 정수라 여기서 전순서가 완성된다           동점 규칙 — 우리 판단
 * </pre>
 *
 * <p>1은 화면 요구에서 온 값이라 데이터가 바뀌어도 재계산하지 않는다. 2·3은 우리 판단이라 근거가
 * 무너지면 바꿀 수 있다.
 *
 * <p><b>실측상 화면을 정하는 것은 2·3이다.</b> 상한 3 기준으로 자를 필요가 있는 Collection 12건 중
 * <b>11건</b>이 3위와 4위의 빈도가 같았고, 16건 중 10건(63%)은 아예 모든 Keyword의 빈도가 1이라
 * 1순위가 아무 일도 하지 않는다. Collection이 Record 1~5건 규모이고 Context 하나가 Keyword를 평균
 * 2개 받아 같은 Keyword가 겹칠 일 자체가 드물기 때문이다. 그래서 동점 규칙을 <b>빈도가 못 가르는
 * 자리를 메우도록</b> 잡았다.
 *
 * <p>2가 하는 일은 <b>같은 빈도 안에서만</b>이다. 빈도 5·4·1이면 결과는 언제나 {@code [5, 4, 1]}이고
 * 축이 순서를 뒤집지 않는다. 빈도가 같은 무리 안에서만 축을 한 바퀴 돌려 카드가 「누구와 · 무엇을 ·
 * 어떤 분위기」처럼 읽히게 한다 — 같은 축 셋은 나열이지 요약이 아니다. 축이 하나뿐이어도 칸은 다
 * 찬다(「축당 1개」로 못 박으면 3축 미만인 6/16이 가진 것보다 적게 나간다).
 *
 * <p>최종 동점을 {@code preset.id}로 푸는 것은 <b>표시값이 언제든 바뀌기 때문이다.</b> 라벨 한 글자를
 * 고치면 카드의 Keyword 구성이 바뀐다 — {@code S15P11A705-252}가 점수 계산의 키를 {@code code}로
 * 못 박은 것과 같은 이유다. {@code id}는 축 블록(1xx {@code COMPANION} / 2xx {@code ACTIVITY} /
 * 3xx {@code ATMOSPHERE} / 4xx {@code SITUATION})이 들어 있어 축 순서를 겸한다.
 *
 * <p><b>Collection 내재 기준이다.</b> 추천 점수의 {@code keywordAffinity}는 보는 사람 기준이므로
 * (요청자 Profile과의 weighted Jaccard, feed-scoring 3.2) 여기 쓰지 않는다. 쓰면 같은 Collection이
 * 사람마다 다른 Keyword를 보여주고, {@code collection_id}로 잡힌 특징 Cache를 표시에 재사용할 수
 * 없으며, <b>남의 카드에 내 Profile이 비친다</b>.
 */
final class FeedKeywordSelector {

	/**
	 * 축 내 순위를 매기기 위한 사전 정렬. 빈도가 높은 것부터, 같으면 {@code preset.id}가 작은 것부터
	 * 보므로 순위가 곧 id 순서가 된다.
	 */
	private static final Comparator<Candidate> BY_WEIGHT_THEN_ID =
		Comparator.comparingDouble(Candidate::weight).reversed()
			.thenComparingInt(candidate -> candidate.label().presetId());

	/**
	 * 최종 표시 순서. <b>빈도가 1순위이며 축이 이것을 뒤집지 않는다</b> — 축 내 순위는 같은 빈도
	 * 안에서만 매겨지므로 서로 다른 빈도끼리는 비교에 끼어들 자리가 없다.
	 *
	 * <p>{@code preset.id}가 UNIQUE이므로 이 비교자는 <b>전순서</b>이며, 같은 입력에 언제나 같은
	 * 결과를 낸다 — 입력 Map의 순회 순서가 달라져도 마찬가지다(feed-tests KW5·KW10).
	 */
	private static final Comparator<Ranked> DISPLAY_ORDER =
		Comparator.comparingDouble((Ranked entry) -> entry.candidate().weight()).reversed()
			.thenComparingInt(Ranked::axisRank)
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
		candidates.sort(BY_WEIGHT_THEN_ID);

		// 순위를 (빈도, 축)마다 따로 센다. 빈도를 키에 넣는 것이 "축은 동점 안에서만 일한다"를
		// 만드는 지점이다 — 빼면 축이 서로 다른 빈도까지 가로질러 순서를 뒤집는다.
		Map<WeightedAxis, Integer> seen = new HashMap<>();
		List<Ranked> ranked = new ArrayList<>(candidates.size());
		for (Candidate candidate : candidates) {
			int axisRank = seen.merge(
				new WeightedAxis(candidate.weight(), candidate.label().category()), 1, Integer::sum);
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

	/**
	 * 축 내 순위의 집계 단위. 정규화 가중치는 Collection마다 <b>같은 값으로 나눈 결과</b>라 빈도가
	 * 같으면 비트까지 같으므로 {@code double}을 키에 넣어도 같은 무리로 묶인다.
	 */
	private record WeightedAxis(double weight, String category) {
	}
}
