package com.pinlog.pinlogback.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordRepository;
import com.pinlog.pinlogback.domain.feed.service.FeedProfile;

import tools.jackson.databind.JsonNode;

/**
 * Keyword 가시성 경계(feed-tests 6장 P3~P7, 공용 §16 15번).
 *
 * <p>P3(타인 특징에 {@code PRIVATE_ONLY}가 쓰이지 않는다)과 P4(본인 Profile에는 쓰인다)를
 * <b>함께</b> 봐야 의미가 있다. 한쪽만 보면 "그냥 안 쓴다"와 구분되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedKeywordVisibilityTests extends FeedFixtures {

	@Autowired
	private FeedKeywordRepository keywordRepository;

	@Test
	void onlyPublicActiveKeywordsCrossTheBoundary() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		String publicCode = uniqueCode("PUB");
		String publicName = uniqueDisplayName("공개");
		String privateCode = uniqueCode("PRIV");
		String privateName = uniqueDisplayName("본인만");
		String blockedCode = uniqueCode("BLK");
		String blockedName = uniqueDisplayName("차단");
		String inactiveCode = uniqueCode("OFF");
		String inactiveName = uniqueDisplayName("폐기");

		long recordId = createRecord(owner, uniqueSeed("visibility"));
		attachKeyword(recordId, insertPreset(publicCode, publicName, "PUBLIC", true));
		attachKeyword(recordId, insertPreset(privateCode, privateName, "PRIVATE_ONLY", true));
		attachKeyword(recordId, insertPreset(blockedCode, blockedName, "BLOCKED", true));
		attachKeyword(recordId, insertPreset(inactiveCode, inactiveName, "PUBLIC", false));
		long collectionId = createCollection(owner, "키워드 책", List.of(recordId));

		// P3·P7 — 타인에게 노출되는 특징은 PUBLIC이고 활성인 Preset뿐이다. 특징은 점수 계산용이라
		// 표시값이 아니라 code로 키를 잡는다(S15P11A705-252).
		Map<Long, Map<String, Double>> features =
			keywordRepository.findPublicKeywordWeights(List.of(collectionId));
		assertThat(features.get(collectionId)).containsOnlyKeys(publicCode);

		// P4 — 반면 본인 Profile은 PRIVATE_ONLY를 쓴다. BLOCKED는 여기서도 빠진다(P6).
		FeedProfile profile = keywordRepository.findProfile(owner);
		assertThat(profile.keywordWeights()).containsOnlyKeys(publicCode, privateCode);
		assertThat(profile.recordCount()).isEqualTo(1);

		// P5·P6 — 응답에는 PUBLIC의 표시값만 실린다. code는 어느 가시성이든 나가지 않는다.
		String payload = feedPayload(viewer);

		JsonNode item = itemOf(parse(payload), collectionId);
		assertThat(item).isNotNull();
		assertThat(keywordsOf(item)).containsExactly(publicName);
		assertThat(payload)
			.doesNotContain(privateName)
			.doesNotContain(blockedName)
			.doesNotContain(inactiveName)
			.doesNotContain(publicCode)
			.doesNotContain(privateCode)
			.doesNotContain(blockedCode)
			.doesNotContain(inactiveCode);
	}

	/** 가중치는 합이 1이 되도록 정규화된다 — 정규화하지 않으면 크기 편향이 되살아난다. */
	@Test
	void keywordWeightsAreNormalized() throws Exception {
		long owner = newMemberId();
		String first = uniqueCode("N1");
		String second = uniqueCode("N2");

		long recordA = createRecord(owner, uniqueSeed("norm-a"));
		long recordB = createRecord(owner, uniqueSeed("norm-b"));
		int firstPreset = insertPreset(first, "PUBLIC", true);
		attachKeyword(recordA, firstPreset);
		attachKeyword(recordB, firstPreset);
		attachKeyword(recordB, insertPreset(second, "PUBLIC", true));
		long collectionId = createCollection(owner, "정규화 책", List.of(recordA, recordB));

		Map<String, Double> weights = keywordRepository
			.findPublicKeywordWeights(List.of(collectionId))
			.get(collectionId);

		assertThat(weights.values().stream().mapToDouble(Double::doubleValue).sum())
			.isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(weights.get(first)).isGreaterThan(weights.get(second));
	}

	/** Keyword가 하나도 없는 사용자는 Profile이 비어 Cold Start로 판정된다(D6). */
	@Test
	void memberWithRecordsButNoKeywordsIsColdStart() throws Exception {
		long owner = newMemberId();
		createRecord(owner, uniqueSeed("cold"));

		FeedProfile profile = keywordRepository.findProfile(owner);

		assertThat(profile.keywordWeights()).isEmpty();
		assertThat(profile.recordCount()).isEqualTo(1);
		assertThat(profile.isColdStart(3)).isTrue();
	}

	@Test
	void emptyCandidateListSkipsTheAggregation() {
		assertThat(keywordRepository.findPublicKeywordWeights(List.of())).isEmpty();
	}

}
