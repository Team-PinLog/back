package com.pinlog.pinlogback.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import tools.jackson.databind.JsonNode;

/**
 * 응답 {@code keywords}의 선정·정렬 계약(feed-tests KW1~KW11, feed-recommendation 3.7.1,
 * <a href="../../../../../../../../docs/ai/proposals/P46-feed-keyword-display-order.md">P46</a>).
 *
 * <p><b>정렬 키는 사전식으로 셋이다.</b>
 *
 * <pre>
 * 1  축 내 순위 ASC    같은 category 안에서 (빈도 DESC, preset id ASC) 로 매긴 1-based 순위
 * 2  빈도 DESC         Collection 안에서 그 Keyword가 붙은 Context 수
 * 3  preset id ASC     UNIQUE 정수라 여기서 전순서가 완성된다
 * </pre>
 *
 * <p>이 순서로 자른 앞 4개가 응답이다. 축을 1순위에 둔 것은 <b>실측이 강제한 선택</b>이다 —
 * 시연 DB 16 Collection 중 10건(63%)이 모든 Keyword의 빈도가 1이고, 4개로 자를 필요가 있는 6건
 * 가운데 4건이 거기 속한다. 빈도만으로는 그 4건에서 한 개도 고르지 못한다(P46 「실측」).
 *
 * <p>기대 순서를 <b>삽입 순서</b>로 적을 수 있는 것은 {@code insertPreset}이 {@code max(id) + 1}로
 * id를 매기기 때문이다. 먼저 넣은 Preset이 항상 작은 id를 갖는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedKeywordDisplayOrderTests extends FeedFixtures {

	/** 같은 테스트가 두 번 조회할 때 중복 Follow(409)를 내지 않기 위한 기록. */
	private final Set<Long> following = new HashSet<>();

	/**
	 * KW1·KW3 — 축이 넷이고 빈도가 전부 같을 때. 상위 4칸이 <b>서로 다른 축</b>으로 채워지고
	 * 같은 축의 두 번째(2·4번째로 넣은 것)는 자리를 얻지 못한다.
	 *
	 * <p>빈도가 갈리지 않는 이 상태가 실측에서 63%다. 여기서 축이 안 갈리면 규칙이 하는 일이 없다.
	 */
	@Test
	void topFourAreFilledWithDistinctAxesWhenEveryKeywordHasTheSameFrequency() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		String companionFirst = uniqueDisplayName("친구와");
		String companionSecond = uniqueDisplayName("가족과");
		String activityFirst = uniqueDisplayName("산책");
		String activitySecond = uniqueDisplayName("한잔");
		String atmosphere = uniqueDisplayName("조용한");
		String situation = uniqueDisplayName("비오는날");

		long recordId = createRecord(owner, uniqueSeed("axis"));
		attachKeyword(recordId, preset(companionFirst, "COMPANION"));
		attachKeyword(recordId, preset(companionSecond, "COMPANION"));
		attachKeyword(recordId, preset(activityFirst, "ACTIVITY"));
		attachKeyword(recordId, preset(activitySecond, "ACTIVITY"));
		attachKeyword(recordId, preset(atmosphere, "ATMOSPHERE"));
		attachKeyword(recordId, preset(situation, "SITUATION"));
		long collectionId = createCollection(owner, "네 축 책", List.of(recordId));

		assertThat(keywordsFor(viewer, owner, collectionId))
			.as("축 내 1순위 넷이 preset id 순으로 나와야 한다")
			.containsExactly(companionFirst, activityFirst, atmosphere, situation);
	}

	/**
	 * KW4 — 축이 하나뿐이어도 4칸을 채운다. 「축당 1개」로 못 박으면 Keyword를 넷 가진 이
	 * Collection이 하나만 내보내게 된다. 실측에서 4축을 다 가진 Collection은 31%뿐이라
	 * 이 경로가 예외가 아니다.
	 */
	@Test
	void singleAxisStillFillsAllFourSlots() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		String first = uniqueDisplayName("하나");
		String second = uniqueDisplayName("둘");
		String third = uniqueDisplayName("셋");
		String fourth = uniqueDisplayName("넷");
		String fifth = uniqueDisplayName("다섯");

		long recordId = createRecord(owner, uniqueSeed("mono"));
		attachKeyword(recordId, preset(first, "ACTIVITY"));
		attachKeyword(recordId, preset(second, "ACTIVITY"));
		attachKeyword(recordId, preset(third, "ACTIVITY"));
		attachKeyword(recordId, preset(fourth, "ACTIVITY"));
		attachKeyword(recordId, preset(fifth, "ACTIVITY"));
		long collectionId = createCollection(owner, "한 축 책", List.of(recordId));

		assertThat(keywordsFor(viewer, owner, collectionId))
			.as("축이 하나여도 4개를 채우고, 동점은 preset id 오름차순으로 푼다")
			.containsExactly(first, second, third, fourth);
	}

	/**
	 * KW2 — 같은 축 안에서는 빈도가 이긴다. Record 두 건에 걸친 Keyword가 축 1순위를 가져가고,
	 * 한 건에만 붙은 같은 축 Keyword는 축 2순위로 밀린다.
	 *
	 * <p>둘째 자리에 다른 축(빈도 1)이 오는 것이 <b>규칙의 의도</b>다 — 축 분산이 1순위이므로 더
	 * 높은 빈도가 뒤로 밀릴 수 있다(feed-recommendation 3.7.1 「감수하는 것」).
	 */
	@Test
	void frequencyDecidesWithinTheSameAxis() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		String repeated = uniqueDisplayName("반복");
		String once = uniqueDisplayName("한번");
		String otherAxis = uniqueDisplayName("다른축");

		long first = createRecord(owner, uniqueSeed("freq-a"));
		long second = createRecord(owner, uniqueSeed("freq-b"));
		int repeatedPreset = preset(repeated, "ACTIVITY");
		attachKeyword(first, repeatedPreset);
		attachKeyword(second, repeatedPreset);
		attachKeyword(first, preset(once, "ACTIVITY"));
		attachKeyword(first, preset(otherAxis, "ATMOSPHERE"));
		long collectionId = createCollection(owner, "빈도 책", List.of(first, second));

		assertThat(keywordsFor(viewer, owner, collectionId))
			.as("축 1순위(빈도 2 → 빈도 1) 다음에 축 2순위가 온다")
			.containsExactly(repeated, otherAxis, once);
	}

	/** KW8 — 4개 미만이면 있는 만큼. 실측 25%가 이 경로이며 정상 경로다. */
	@Test
	void fewerThanFourKeywordsAreReturnedAsIs() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		String companion = uniqueDisplayName("동행");
		String activity = uniqueDisplayName("활동");

		long recordId = createRecord(owner, uniqueSeed("short"));
		attachKeyword(recordId, preset(companion, "COMPANION"));
		attachKeyword(recordId, preset(activity, "ACTIVITY"));
		long collectionId = createCollection(owner, "짧은 책", List.of(recordId));

		assertThat(keywordsFor(viewer, owner, collectionId)).containsExactly(companion, activity);
	}

	/** KW9 — AI가 아직 안 붙은 Collection은 빈 배열이다. 오류가 아니다(공용 §16 시나리오 21). */
	@Test
	void collectionWithoutKeywordsAnswersWithAnEmptyArray() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		long collectionId = publishedCollection(owner, uniqueSeed("empty"));

		assertThat(keywordsFor(viewer, owner, collectionId)).isEmpty();
	}

	/**
	 * KW10 — 같은 요청 두 번에 같은 순서. 이 단언이 없으면 화면이 새로고침마다 흔들리는 것을
	 * 아무도 못 잡는다. 동점(빈도가 전부 1)을 일부러 섞어 둔다.
	 */
	@Test
	void repeatingTheRequestYieldsTheSameOrder() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		long recordId = createRecord(owner, uniqueSeed("stable"));
		for (String category : List.of("COMPANION", "ACTIVITY", "ACTIVITY", "ATMOSPHERE", "SITUATION")) {
			attachKeyword(recordId, preset(uniqueDisplayName("안정"), category));
		}
		long collectionId = createCollection(owner, "안정 책", List.of(recordId));

		List<String> first = keywordsFor(viewer, owner, collectionId);
		List<String> second = keywordsFor(viewer, owner, collectionId);

		assertThat(first).hasSize(4);
		assertThat(second).as("같은 요청에 항상 같은 순서여야 한다").containsExactlyElementsOf(first);
	}

	/**
	 * KW11 — 표시값을 바꿔도 선정 결과와 순서가 그대로다.
	 *
	 * <p>가나다순으로 정렬하면 라벨 한 글자를 고친 날 카드에 뜨는 Keyword <b>구성 자체가</b>
	 * 바뀐다. 오류도 안 나고 다른 테스트도 안 깨진다 — {@code S15P11A705-252}가 점수 계산의 키를
	 * {@code code}로 못 박은 것과 같은 이유이며, 그래서 순서도 불변 식별자에 건다.
	 */
	@Test
	void renamingAPresetMovesNeitherTheSelectionNorTheOrder() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		String renamedCode = uniqueCode("RENAME");
		String before = uniqueDisplayName("가장앞");
		String after = uniqueDisplayName("힣가장뒤");

		long recordId = createRecord(owner, uniqueSeed("rename"));
		attachKeyword(recordId, insertPreset(renamedCode, before, "ACTIVITY", "PUBLIC", true));
		attachKeyword(recordId, preset(uniqueDisplayName("나중"), "ACTIVITY"));
		attachKeyword(recordId, preset(uniqueDisplayName("다음"), "ACTIVITY"));
		long collectionId = createCollection(owner, "개명 순서 책", List.of(recordId));

		List<String> beforeRename = keywordsFor(viewer, owner, collectionId);
		assertThat(beforeRename).startsWith(before);

		jdbcTemplate.update(
			"UPDATE ai.keyword_preset SET display_name = ? WHERE code = ?", after, renamedCode);

		assertThat(keywordsFor(viewer, owner, collectionId))
			.as("표시값을 가나다 끝으로 바꿔도 순서는 preset id 그대로여야 한다")
			.containsExactly(after, beforeRename.get(1), beforeRename.get(2));
	}

	/** 표시값만 정하면 되는 경우의 축약. {@code code}는 응답에 안 나가므로 아무 값이나 된다. */
	private int preset(String displayName, String category) {
		return insertPreset(uniqueCode("ORD"), displayName, category, "PUBLIC", true);
	}

	/**
	 * 후보 풀에 확실히 들어가도록 <b>팔로우한 뒤</b> 조회한다. 공유 컨테이너라 다른 테스트가 만든
	 * Collection이 계속 쌓이는데, 팔로우 채널은 후보 병합에서 가장 앞이고 {@code followSignal}이
	 * 1.0이라 점수에서도 앞선다 — 내 Collection이 페이지 밖으로 밀려 테스트가 산발적으로 깨지는
	 * 것을 막는다.
	 */
	private List<String> keywordsFor(long viewer, long owner, long collectionId) throws Exception {
		if (!following.contains(collectionId)) {
			follow(viewer, collectionId);
			following.add(collectionId);
		}
		JsonNode item = itemOf(parse(feedPayload(viewer)), collectionId);
		assertThat(item).as("만든 Collection이 후보에 들어오지 않았다. owner=%d", owner).isNotNull();
		return keywordsOf(item);
	}
}
