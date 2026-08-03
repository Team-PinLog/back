package com.pinlog.pinlogback.domain.feed.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordLabel;

/**
 * 표시 Keyword 선정·정렬 규칙(feed-tests KW1~KW7, feed-recommendation 3.7.1, P46).
 *
 * <p>DB도 Redis도 없는 순수 자바다 — 규칙 자체가 산술이므로 통합에 얹으면 실패했을 때 규칙이
 * 틀린 것인지 픽스처가 틀린 것인지 가려내기 어렵다. 통합 쪽은
 * {@code FeedKeywordDisplayOrderTests}가 "이 규칙이 실제 응답까지 도달하는가"만 본다.
 *
 * <p>가중치는 Collection마다 합이 1이 되도록 정규화된 값이 들어온다. 정규화는 <b>같은 값으로
 * 나눈 것</b>이라 순서가 원래 빈도와 같으므로, 아래 테스트는 읽기 쉬운 정수를 그대로 쓴다.
 */
class FeedKeywordSelectorTests {

	private static final int LIMIT = 4;

	/**
	 * KW3 — 빈도가 전부 같을 때 축이 가른다. 실측에서 Collection의 63%가 이 상태이고, 4개로 자를
	 * 필요가 있는 6건 중 4건이 여기 속한다. <b>빈도만 쓰면 그 4건이 사실상 무작위 절단이 된다.</b>
	 */
	@Test
	void distinctAxesFillTheTopSlotsWhenEveryWeightIsEqual() {
		Map<String, Double> weights = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(weights, labels, "COMPANION_A", 101, "친구와", "COMPANION", 1);
		put(weights, labels, "COMPANION_B", 102, "가족과", "COMPANION", 1);
		put(weights, labels, "ACTIVITY_A", 201, "산책", "ACTIVITY", 1);
		put(weights, labels, "ACTIVITY_B", 202, "한잔", "ACTIVITY", 1);
		put(weights, labels, "ATMOSPHERE_A", 301, "조용한", "ATMOSPHERE", 1);
		put(weights, labels, "SITUATION_A", 401, "비 오는 날", "SITUATION", 1);

		assertThat(FeedKeywordSelector.select(weights, labels, LIMIT))
			.containsExactly("친구와", "산책", "조용한", "비 오는 날");
	}

	/** KW2 — 같은 축 안에서는 빈도가 이긴다. 축 1순위를 누가 가져가는지가 여기서 갈린다. */
	@Test
	void frequencyDecidesWhichKeywordOwnsItsAxis() {
		Map<String, Double> weights = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(weights, labels, "ACTIVITY_LOW", 201, "산책", "ACTIVITY", 1);
		put(weights, labels, "ACTIVITY_HIGH", 202, "한잔", "ACTIVITY", 3);
		put(weights, labels, "ATMOSPHERE_A", 301, "조용한", "ATMOSPHERE", 2);

		assertThat(FeedKeywordSelector.select(weights, labels, LIMIT))
			.as("id가 더 커도 빈도가 높으면 축 1순위를 가져간다")
			.containsExactly("한잔", "조용한", "산책");
	}

	/**
	 * 축 분산이 1순위이므로 <b>더 높은 빈도가 뒤로 밀린다.</b> 규칙의 의도이며 결함이 아니다
	 * (feed-recommendation 3.7.1 「감수하는 것」). 명세에 적어 둔 대가를 실행으로도 고정한다.
	 */
	@Test
	void axisSpreadOutranksRawFrequencyAcrossAxes() {
		Map<String, Double> weights = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(weights, labels, "ACTIVITY_TOP", 201, "한잔", "ACTIVITY", 5);
		put(weights, labels, "ACTIVITY_SECOND", 202, "산책", "ACTIVITY", 4);
		put(weights, labels, "ATMOSPHERE_A", 301, "조용한", "ATMOSPHERE", 1);

		assertThat(FeedKeywordSelector.select(weights, labels, LIMIT))
			.as("[5, 1, 4]가 되는 것이 규칙의 의도다")
			.containsExactly("한잔", "조용한", "산책");
	}

	/**
	 * KW1·KW4 — 축이 하나뿐이어도 4칸을 채운다.
	 *
	 * <p>「축당 1개」로 못 박으면 이 Collection은 Keyword를 다섯 가지고도 하나만 내보낸다. 실측에서
	 * 4축을 다 가진 Collection은 31%뿐이라 이 경로가 예외가 아니다.
	 */
	@Test
	void singleAxisStillFillsTheLimit() {
		Map<String, Double> weights = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(weights, labels, "A", 201, "하나", "ACTIVITY", 1);
		put(weights, labels, "B", 202, "둘", "ACTIVITY", 1);
		put(weights, labels, "C", 203, "셋", "ACTIVITY", 1);
		put(weights, labels, "D", 204, "넷", "ACTIVITY", 1);
		put(weights, labels, "E", 205, "다섯", "ACTIVITY", 1);

		assertThat(FeedKeywordSelector.select(weights, labels, LIMIT))
			.containsExactly("하나", "둘", "셋", "넷");
	}

	/**
	 * KW5 — 축·빈도가 모두 같으면 {@code preset.id} 오름차순이다. <b>입력 Map의 순회 순서를 바꿔도
	 * 결과가 같아야 한다</b> — 순회 순서에 기대면 Cache 적중 여부나 JDK 판이 바뀐 날 화면이 흔들린다.
	 */
	@Test
	void theResultDoesNotDependOnTheInputIterationOrder() {
		Map<String, Double> insertionOrder = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(insertionOrder, labels, "Z_LAST", 204, "넷", "ACTIVITY", 1);
		put(insertionOrder, labels, "A_FIRST", 201, "하나", "ACTIVITY", 1);
		put(insertionOrder, labels, "M_MIDDLE", 202, "둘", "ACTIVITY", 1);
		put(insertionOrder, labels, "B_OTHER", 203, "셋", "ACTIVITY", 1);

		assertThat(FeedKeywordSelector.select(insertionOrder, labels, LIMIT))
			.containsExactly("하나", "둘", "셋", "넷");
		assertThat(FeedKeywordSelector.select(new TreeMap<>(insertionOrder), labels, LIMIT))
			.as("code 사전순으로 순회해도 결과가 같아야 한다")
			.containsExactly("하나", "둘", "셋", "넷");
	}

	/**
	 * KW6 — 표시값을 못 찾은 {@code code}는 빠지고 <b>다음 후보가 그 자리를 채운다</b>.
	 *
	 * <p>거르기를 자르기 뒤에 두면 4개를 요청했는데 3개가 나가고, 증상이 Preset 폐기 시점에만
	 * 산발적으로 드러난다. {@code code}로 대신 채우는 폴백은 그 자체가 08 §6.1 위반이다.
	 */
	@Test
	void unresolvableCodeYieldsItsSlotToTheNextCandidate() {
		Map<String, Double> weights = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(weights, labels, "A", 201, "하나", "ACTIVITY", 1);
		put(weights, labels, "B", 202, "둘", "ACTIVITY", 1);
		put(weights, labels, "C", 203, "셋", "ACTIVITY", 1);
		put(weights, labels, "D", 204, "넷", "ACTIVITY", 1);
		put(weights, labels, "E", 205, "다섯", "ACTIVITY", 1);
		labels.remove("B");

		assertThat(FeedKeywordSelector.select(weights, labels, LIMIT))
			.as("폐기된 Preset 자리를 비우지 않고 다음 후보가 채운다")
			.containsExactly("하나", "셋", "넷", "다섯");
	}

	/** KW7 — 서로 다른 {@code code}의 표시값이 겹치면 앞의 것만 남고 자리는 다음 후보가 채운다. */
	@Test
	void duplicateDisplayNameYieldsItsSlotToTheNextCandidate() {
		Map<String, Double> weights = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(weights, labels, "A", 201, "하나", "ACTIVITY", 1);
		put(weights, labels, "B", 202, "하나", "ACTIVITY", 1);
		put(weights, labels, "C", 203, "셋", "ACTIVITY", 1);
		put(weights, labels, "D", 204, "넷", "ACTIVITY", 1);
		put(weights, labels, "E", 205, "다섯", "ACTIVITY", 1);

		assertThat(FeedKeywordSelector.select(weights, labels, LIMIT))
			.containsExactly("하나", "셋", "넷", "다섯");
	}

	/** KW8·KW9 — 상한 미만은 있는 만큼, 하나도 없으면 빈 목록. 오류가 아니다. */
	@Test
	void fewerThanTheLimitAndNoneAtAllAreBothNormal() {
		Map<String, Double> weights = new LinkedHashMap<>();
		Map<String, FeedKeywordLabel> labels = new LinkedHashMap<>();
		put(weights, labels, "A", 201, "하나", "ACTIVITY", 1);
		put(weights, labels, "B", 301, "둘", "ATMOSPHERE", 1);

		assertThat(FeedKeywordSelector.select(weights, labels, LIMIT)).containsExactly("하나", "둘");
		assertThat(FeedKeywordSelector.select(Map.of(), labels, LIMIT)).isEmpty();
		assertThat(FeedKeywordSelector.select(weights, Map.of(), LIMIT)).isEmpty();
	}

	private void put(Map<String, Double> weights, Map<String, FeedKeywordLabel> labels, String code,
		int presetId, String displayName, String category, double weight) {
		weights.put(code, weight);
		labels.put(code, new FeedKeywordLabel(presetId, displayName, category));
	}
}
