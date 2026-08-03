package com.pinlog.pinlogback.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordLabel;
import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordRepository;

import tools.jackson.databind.JsonNode;

/**
 * 응답 경계에서만 {@code display_name}으로 옮긴다(API 명세 08 §6.1, back#146).
 *
 * <p>이 클래스가 고정하는 것은 <b>두 문자열이 서로 다른 층에 있다</b>는 사실이다.
 *
 * <table border="1">
 *   <caption>Keyword 문자열이 쓰이는 두 층</caption>
 *   <tr><th>층</th><th>키</th><th>이유</th></tr>
 *   <tr><td>점수 계산(Profile · Collection 특징)</td><td>{@code code}</td>
 *       <td>불변 식별자. 표시값이 바뀌어도 과거 Profile과의 매칭이 어긋나지 않는다</td></tr>
 *   <tr><td>응답 {@code keywords}</td><td>{@code display_name}</td>
 *       <td>화면에 그대로 그려지는 라벨. {@code code}는 노출 금지</td></tr>
 * </table>
 *
 * <p>{@code display_name}을 점수 계산의 키로 써도 양쪽 키가 같기만 하면 Jaccard는 성립하므로
 * <b>테스트도 안 깨지고 오류도 안 난다</b> — 표시값을 고친 날 추천 품질만 조용히 나빠진다. 그래서
 * {@link #renamingAPresetDoesNotMoveTheScoringKey()}가 그 결정을 실행으로 붙잡아 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedKeywordDisplayNameTests extends FeedFixtures {

	@Autowired
	private FeedKeywordRepository keywordRepository;

	/** 응답은 한글 표시값이고 영문 {@code code}는 어디에도 없다. */
	@Test
	void feedItemsCarryDisplayNamesAndNeverTheCode() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		String walkCode = uniqueCode("WALK");
		String walkName = uniqueDisplayName("산책");
		String cafeCode = uniqueCode("COFFEE");
		String cafeName = uniqueDisplayName("카페");

		long recordId = createRecord(owner, uniqueSeed("display"));
		attachKeyword(recordId, insertPreset(walkCode, walkName, "PUBLIC", true));
		attachKeyword(recordId, insertPreset(cafeCode, cafeName, "PUBLIC", true));
		long collectionId = createCollection(owner, "표시값 책", List.of(recordId));

		String payload = feedPayload(viewer);
		JsonNode item = itemOf(parse(payload), collectionId);

		assertThat(item).as("만든 Collection이 후보에 들어오지 않았다").isNotNull();
		assertThat(keywordsOf(item))
			.as("keywords는 keyword_preset.display_name이어야 한다(08 §6.1)")
			.containsExactlyInAnyOrder(walkName, cafeName);
		assertThat(payload)
			.as("응답 어디에도 keyword_preset.code가 나가지 않는다")
			.doesNotContain(walkCode)
			.doesNotContain(cafeCode);
	}

	/**
	 * 표시값을 바꿔도 점수 계산의 키는 그대로 {@code code}다.
	 *
	 * <p>이 단언이 없으면 "응답만 맞으면 됐다"는 이유로 내부 키를 {@code display_name}으로 옮기는
	 * 변경이 아무 신호 없이 들어온다. 그 변경은 기능이 아니라 <b>이력</b>을 깨뜨린다 — 표시값이
	 * 바뀐 시점 이전에 계산된 Profile과 매칭이 어긋나고, 증상은 오류가 아니라 추천 품질 저하다.
	 */
	@Test
	void renamingAPresetDoesNotMoveTheScoringKey() throws Exception {
		long owner = newMemberId();
		String code = uniqueCode("QUIET");
		String before = uniqueDisplayName("조용한");
		String after = uniqueDisplayName("한적한");

		long recordId = createRecord(owner, uniqueSeed("rename"));
		attachKeyword(recordId, insertPreset(code, before, "PUBLIC", true));
		long collectionId = createCollection(owner, "개명 책", List.of(recordId));

		Map<String, Double> beforeWeights =
			keywordRepository.findPublicKeywordWeights(List.of(collectionId)).get(collectionId);
		Map<String, Double> beforeProfile = keywordRepository.findProfile(owner).keywordWeights();
		assertThat(beforeWeights).containsOnlyKeys(code);
		assertThat(beforeProfile).containsKey(code);

		jdbcTemplate.update(
			"UPDATE ai.keyword_preset SET display_name = ? WHERE code = ?", after, code);

		assertThat(keywordRepository.findPublicKeywordWeights(List.of(collectionId)).get(collectionId))
			.as("Collection 특징의 키는 표시값 변경에 영향받지 않는다")
			.isEqualTo(beforeWeights);
		assertThat(keywordRepository.findProfile(owner).keywordWeights())
			.as("Profile의 키도 마찬가지다 — 두 분포의 키가 같아야 Jaccard가 성립한다")
			.isEqualTo(beforeProfile);
	}

	/**
	 * 표시값 조회도 {@code PUBLIC}·활성만 본다. 특징 집계와 같은 화이트리스트라 어느 쪽 필터가
	 * 무너져도 감춰야 할 라벨이 응답에 실리지 않는다.
	 *
	 * <p>표시값을 못 찾은 code가 <b>빠지는</b> 쪽이 계약이라는 것은 대역으로 고정한다
	 * ({@code FeedServiceTests}) — 두 쿼리가 같은 요청 안에서 어긋나는 상황은 DB로 만들 수 없다.
	 */
	@Test
	void displayNameLookupAppliesTheSameVisibilityWhitelist() throws Exception {
		String publicCode = uniqueCode("PUB");
		String publicName = uniqueDisplayName("공개");
		String privateCode = uniqueCode("PRIV");
		String blockedCode = uniqueCode("BLK");
		String inactiveCode = uniqueCode("OFF");

		int publicId = insertPreset(publicCode, publicName, "ATMOSPHERE", "PUBLIC", true);
		insertPreset(privateCode, uniqueDisplayName("본인만"), "PRIVATE_ONLY", true);
		insertPreset(blockedCode, uniqueDisplayName("차단"), "BLOCKED", true);
		insertPreset(inactiveCode, uniqueDisplayName("폐기"), "PUBLIC", false);

		assertThat(keywordRepository.findPublicKeywordLabels(
			List.of(publicCode, privateCode, blockedCode, inactiveCode)))
			.containsExactly(Map.entry(publicCode,
				new FeedKeywordLabel(publicId, publicName, "ATMOSPHERE")));
	}

	/**
	 * 표시값과 함께 {@code id}·{@code category}를 들고 오는 것은 <b>표시 정렬이 그 둘을 쓰기
	 * 때문이다</b>(feed-recommendation 3.7.1). 축을 따로 조회하면 그만큼 왕복이 는다.
	 */
	@Test
	void theLabelCarriesTheSortingKeysNotJustTheLabel() {
		String code = uniqueCode("AXIS");
		String name = uniqueDisplayName("동행");
		int presetId = insertPreset(code, name, "COMPANION", "PUBLIC", true);

		assertThat(keywordRepository.findPublicKeywordLabels(List.of(code)).get(code))
			.isEqualTo(new FeedKeywordLabel(presetId, name, "COMPANION"));
	}

	@Test
	void resolvingLabelsForNoCodesSkipsTheQuery() {
		assertThat(keywordRepository.findPublicKeywordLabels(List.of())).isEmpty();
	}
}
