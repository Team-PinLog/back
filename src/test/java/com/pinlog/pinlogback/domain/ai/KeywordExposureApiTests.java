package com.pinlog.pinlogback.domain.ai;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.pinlog.pinlogback.support.CoreApiFixtures;

import tools.jackson.databind.JsonNode;

/**
 * 조회 응답의 Keyword 노출(S15P11A705-240, back#145).
 *
 * <p>Keyword는 저장되지 않고 {@code ai.context_keyword}에서 읽기 시점 집계로 파생된다(BD-18).
 * 이 테스트가 고정하는 것은 둘이다 — <b>AI 처리가 끝난 뒤에는 조회 응답이 실제로 채워진다</b>는
 * 것과, <b>Visibility 경계가 응답 종류(소유자·타인)에 따라 다르게 걸린다</b>는 것(04 §2 표).
 *
 * <ul>
 *   <li>소유자 응답(§5.2 상세·§7.3 소유자 Record 페이지) — {@code PUBLIC} + {@code PRIVATE_ONLY}</li>
 *   <li>타인 응답(§7.3 카드·§9.3·§8.1 목록) — {@code PUBLIC}만</li>
 *   <li>{@code BLOCKED} — 어디에도 없다</li>
 * </ul>
 *
 * <p>운영 AI 없이 검증한다 — FastAPI가 채울 자리를 테스트가 직접 넣고
 * {@code keyword_status}를 {@code COMPLETED}로 올린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("조회 응답의 Keyword 노출")
class KeywordExposureApiTests extends CoreApiFixtures {

	private static final String PUBLIC_NAME = "산책";
	private static final String PRIVATE_NAME = "직장 근처";
	private static final String BLOCKED_NAME = "차단됨";

	@Test
	@DisplayName("소유자 Record 상세는 PUBLIC과 PRIVATE_ONLY를 한글로 보여준다")
	void ownerRecordDetailShowsOwnerScopeKeywords() throws Exception {
		long owner = newMemberId();
		long recordId = recordWithCompletedKeywords(owner, "kw-owner-detail");

		JsonNode detail = ownerRecordDetail(owner, recordId);

		assertThat(keywordList(detail.at("/keywords")))
			.contains(PUBLIC_NAME, PRIVATE_NAME)
			.doesNotContain(BLOCKED_NAME);
	}

	@Test
	@DisplayName("Record 생성 응답의 keywords는 기존 키워드가 있어도 빈 배열이다")
	void recordCreateResponseKeepsKeywordsEmpty() throws Exception {
		long owner = newMemberId();
		String placeKey = uniquePlace("kw-create");
		recordWithCompletedKeywords(owner, placeKey);

		// 같은 장소에 다시 생성 → CONTEXT_ADDED. Record에는 이미 완료된 Keyword가 있다.
		JsonNode response = parse(mockMvc.perform(
				MockMvcRequestBuilders.post("/v1/records").with(loginAs(owner))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						\t"place": {
						\t\t"kakaoPlaceId": "%s",
						\t\t"name": "장소",
						\t\t"address": "주소",
						\t\t"lat": 37.5,
						\t\t"lng": 127.0
						\t},
						\t"contextBody": "두 번째 맥락"
						}
						""".formatted(placeKey)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());

		assertThat(keywordList(response.at("/data/keywords"))).isEmpty();
	}

	@Test
	@DisplayName("소유자 Collection 상세의 Record 페이지에 키워드가 실린다")
	void ownerCollectionDetailRecordsCarryKeywords() throws Exception {
		long owner = newMemberId();
		long recordId = recordWithCompletedKeywords(owner, "kw-owner-col");
		long collectionId = createCollection(owner, "소유자 상세", List.of(recordId));

		JsonNode detail = collectionDetail(owner, collectionId);

		assertThat(keywordList(detail.at("/records/items/0/keywords")))
			.contains(PUBLIC_NAME, PRIVATE_NAME)
			.doesNotContain(BLOCKED_NAME);
	}

	@Test
	@DisplayName("타인 Collection 상세의 Record 카드는 PUBLIC만 보여준다")
	void strangerCollectionDetailCardsShowOnlyPublic() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long recordId = recordWithCompletedKeywords(owner, "kw-public-card");
		long collectionId = createCollection(owner, "타인 카드", List.of(recordId));

		JsonNode detail = collectionDetail(stranger, collectionId);

		assertThat(keywordList(detail.at("/records/items/0/keywords")))
			.contains(PUBLIC_NAME)
			.doesNotContain(PRIVATE_NAME, BLOCKED_NAME);
	}

	@Test
	@DisplayName("shelf 목록 항목에 컬렉션 키워드가 PUBLIC만 실린다")
	void shelfItemsCarryPublicCollectionKeywords() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long recordId = recordWithCompletedKeywords(author, "kw-shelf");
		long collectionId = createCollection(author, "shelf 키워드", List.of(recordId));

		JsonNode data = parse(mockMvc.perform(
				get("/v1/feed/collections/{collectionId}/shelf", collectionId).with(loginAs(stranger)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");

		assertThat(keywordList(data.at("/collections/items/0/keywords")))
			.contains(PUBLIC_NAME)
			.doesNotContain(PRIVATE_NAME, BLOCKED_NAME);
	}

	@Test
	@DisplayName("팔로우 책장 목록(§9.3) 항목에 컬렉션 키워드가 실린다")
	void followedCollectionsCarryKeywords() throws Exception {
		long author = newMemberId();
		long follower = newMemberId();
		long recordId = recordWithCompletedKeywords(author, "kw-followed");
		long collectionId = createCollection(author, "팔로우 목록", List.of(recordId));
		long followId = follow(follower, collectionId);

		JsonNode data = parse(mockMvc.perform(
				get("/v1/follows/{followId}/collections", followId).with(loginAs(follower)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");

		assertThat(keywordList(data.at("/items/0/keywords")))
			.contains(PUBLIC_NAME)
			.doesNotContain(PRIVATE_NAME, BLOCKED_NAME);
	}

	@Test
	@DisplayName("Collection에서 Record를 빼면 그 Record의 키워드가 목록에서 사라진다")
	void removedRecordNoLongerContributesToCollectionKeywords() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long keeps = recordWithCompletedKeywords(author, "kw-keep");
		long removedRecord = createRecord(author, uniquePlace("kw-gone"), "빠질 기록");
		int lonely = insertPreset("LONELY_ONLY", "혼자만", "PUBLIC", true);
		attachKeyword(removedRecord, lonely);
		completeKeywords(removedRecord);
		long collectionId = createCollection(author, "제거 검증", List.of(keeps, removedRecord));

		mockMvc.perform(delete("/v1/collections/{collectionId}/records/{recordId}", collectionId, removedRecord)
				.with(loginAs(author)))
			.andExpect(status().isNoContent());

		JsonNode data = parse(mockMvc.perform(
				get("/v1/feed/collections/{collectionId}/shelf", collectionId).with(loginAs(stranger)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");

		assertThat(keywordList(data.at("/collections/items/0/keywords")))
			.contains(PUBLIC_NAME)
			.doesNotContain("혼자만");
	}

	@Test
	@DisplayName("여러 컬렉션이 한 페이지에 있어도 키워드가 각자의 것으로 붙는다")
	void keywordsAreGroupedPerCollection() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long first = recordWithCompletedKeywords(author, "kw-page-1");
		long firstCollection = createCollection(author, "첫째", List.of(first));

		long second = createRecord(author, uniquePlace("kw-page-2"), "둘째 기록");
		int retro = insertPreset("RETRO_TEST", "복고", "PUBLIC", true);
		attachKeyword(second, retro);
		completeKeywords(second);
		createCollection(author, "둘째", List.of(second));

		JsonNode data = parse(mockMvc.perform(
				get("/v1/feed/collections/{collectionId}/shelf", firstCollection).with(loginAs(stranger)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");

		for (JsonNode item : data.at("/collections/items")) {
			List<String> keywords = keywordList(item.at("/keywords"));
			if ("첫째".equals(item.at("/title").asString())) {
				assertThat(keywords).contains(PUBLIC_NAME).doesNotContain("복고");
			} else {
				assertThat(keywords).contains("복고").doesNotContain(PUBLIC_NAME);
			}
		}
	}

	// ── fixtures ────────────────────────────────────────────────────────────

	/** PUBLIC·PRIVATE_ONLY·BLOCKED 프리셋을 하나씩 붙이고 COMPLETED로 올린 Record. */
	private long recordWithCompletedKeywords(long memberId, String placePrefix) throws Exception {
		long recordId = createRecord(memberId, uniquePlace(placePrefix), "맥락 " + placePrefix);
		attachKeyword(recordId, insertPreset("PUB_" + placePrefix, PUBLIC_NAME, "PUBLIC", true));
		attachKeyword(recordId, insertPreset("PRV_" + placePrefix, PRIVATE_NAME, "PRIVATE_ONLY", true));
		attachKeyword(recordId, insertPreset("BLK_" + placePrefix, BLOCKED_NAME, "BLOCKED", true));
		completeKeywords(recordId);
		return recordId;
	}

	/**
	 * FastAPI가 채울 자리를 대신한다({@code FeedFixtures}와 같은 선택). {@code embedding}은
	 * {@code NOT NULL}이지만 이 검증은 벡터를 쓰지 않으므로 0 벡터로 채운다.
	 */
	private int insertPreset(String code, String displayName, String visibility, boolean active) {
		Integer id = jdbcTemplate.queryForObject(
			"SELECT coalesce(max(id), 0) + 1 FROM ai.keyword_preset", Integer.class);
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, 'MOOD', '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, ?, 1)
			""", id, code, displayName, visibility, active);
		return id;
	}

	private void attachKeyword(long recordId, int presetId) {
		List<Long> contextIds = jdbcTemplate.queryForList(
			"SELECT id FROM core.context WHERE record_id = ? AND deleted_at IS NULL", Long.class, recordId);
		contextIds.forEach(contextId -> jdbcTemplate.update("""
			INSERT INTO ai.context_keyword (context_id, keyword_id, confidence, preset_version)
			VALUES (?, ?, 0.900, 1)
			""", contextId, presetId));
	}

	/** Context 생성이 넣어 둔 PENDING 상태를 완료로 올린다 — 집계는 COMPLETED만 본다. */
	private void completeKeywords(long recordId) {
		jdbcTemplate.update("""
			UPDATE ai.context_ai_state SET keyword_status = 'COMPLETED'
			WHERE context_id IN (SELECT id FROM core.context WHERE record_id = ? AND deleted_at IS NULL)
			""", recordId);
	}

	private String uniquePlace(String prefix) {
		return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
	}

	private JsonNode ownerRecordDetail(long memberId, long recordId) throws Exception {
		return parse(mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");
	}

	private JsonNode collectionDetail(long viewerId, long collectionId) throws Exception {
		return parse(mockMvc.perform(get("/v1/collections/{collectionId}", collectionId).with(loginAs(viewerId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");
	}

	private List<String> keywordList(JsonNode keywords) {
		return keywords.valueStream().map(JsonNode::asString).toList();
	}
}
