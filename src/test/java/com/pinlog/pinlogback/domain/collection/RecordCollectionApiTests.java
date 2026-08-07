package com.pinlog.pinlogback.domain.collection;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import com.pinlog.pinlogback.support.CoreApiFixtures;

import tools.jackson.databind.JsonNode;

/**
 * {@code GET /v1/records/{recordId}/collections}의 계약(S15P11A705-391).
 *
 * <p>이 목록의 상한은 "내 활성 Collection 전부"이며 열려 있다 — 한 Record가 담길 수 있는
 * Collection 수에 제한이 없다(명세 1.9의 방어 상한은 요청 1회의 배열 크기다). 그래서 페이징이
 * 장식이 아니라 계약이고, 커서 검증을 이 클래스가 갖는다.
 *
 * <p>Core 데이터는 실제 API로 만든다. 직접 INSERT하면 {@code record_count}와 연결 링크를 손으로
 * 재현해야 하고, 그때부터 검증 대상이 "기능"에서 "내 픽스처"로 옮겨간다({@code CoreApiFixtures}).
 *
 * <p>테스트 간 DB를 비우지 않으므로 단언은 내가 만든 collectionId로 걸러낸 부분에만 건다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordCollectionApiTests extends CoreApiFixtures {

	@Test
	void listsMyCollectionsContainingTheRecordOldestFirst() throws Exception {
		long me = newMemberId();
		long recordId = createRecord(me, seed("rc-basic"), "저장 이유");
		long other = createRecord(me, seed("rc-other"), "다른 기록");
		long first = createCollection(me, "먼저 만든 책", List.of(recordId));
		long second = createCollection(me, "나중 만든 책", List.of(recordId));
		long unrelated = createCollection(me, "관계없는 책", List.of(other));

		JsonNode page = list(me, recordId, "");

		assertThat(collectionIdsOf(page)).containsExactly(first, second).doesNotContain(unrelated);
	}

	@Test
	void recordOfAnotherMemberIs404() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long recordId = createRecord(owner, seed("rc-priv"), "남의 기록");
		createCollection(owner, "남의 책", List.of(recordId));

		mockMvc.perform(get("/v1/records/{recordId}/collections", recordId).with(loginAs(stranger)))
			.andExpect(status().isNotFound());
	}

	@Test
	void missingRecordIs404() throws Exception {
		long me = newMemberId();

		mockMvc.perform(get("/v1/records/{recordId}/collections", 99_999_999L).with(loginAs(me)))
			.andExpect(status().isNotFound());
	}

	@Test
	void recordInNoCollectionIsAnEmptyPageNot404() throws Exception {
		long me = newMemberId();
		long recordId = createRecord(me, seed("rc-empty"), "아직 안 담긴 기록");

		JsonNode page = list(me, recordId, "");

		assertThat(collectionIdsOf(page)).isEmpty();
		// nextCursor는 키가 빠지는 것이 아니라 명시적 null로 직렬화된다(CursorPage의 계약).
		// jsonPath().doesNotExist()로 단언하면 그 구분이 사라진다.
		assertThat(page.at("/data/nextCursor").isNull()).isTrue();
		assertThat(page.at("/data/hasNext").asBoolean()).isFalse();
	}

	@Test
	void removedLinkAndDeletedCollectionAreExcluded() throws Exception {
		long me = newMemberId();
		long recordId = createRecord(me, seed("rc-del"), "삭제 검증용");
		long keep = createCollection(me, "남는 책", List.of(recordId));
		long unlinked = createCollection(me, "연결 끊긴 책", List.of(recordId));
		long deleted = createCollection(me, "삭제된 책", List.of(recordId));
		jdbcTemplate.update("""
			UPDATE core.collection_record SET deleted_at = now()
			WHERE collection_id = ? AND record_id = ?
			""", unlinked, recordId);
		softDelete("core.collection", deleted);

		JsonNode page = list(me, recordId, "");

		assertThat(collectionIdsOf(page)).containsExactly(keep);
	}

	@Test
	void cardCarriesCoverImageUrlAndPublicKeywords() throws Exception {
		long me = newMemberId();
		long recordId = createRecord(me, seed("rc-card"), "표지와 키워드");
		attachKeyword(recordId, insertPreset("RCOL_PUB", "공개키워드", "PUBLIC"));
		attachKeyword(recordId, insertPreset("RCOL_BLK", "차단키워드", "BLOCKED"));
		completeKeywords(recordId);
		long collectionId = createCollection(me, "표지 있는 책", List.of(recordId));
		mockMvc.perform(patch("/v1/collections/{collectionId}", collectionId).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"coverImageUrl\": \"" + COVER_URL + "\"}"))
			.andExpect(status().isOk());

		JsonNode item = list(me, recordId, "").at("/data/items/0");

		assertThat(item.at("/collectionId").asLong()).isEqualTo(collectionId);
		assertThat(item.at("/coverImageUrl").asString()).isEqualTo(COVER_URL);
		assertThat(item.at("/recordCount").asInt()).isEqualTo(1);
		assertThat(keywordsOf(item)).containsExactly("공개키워드");
	}

	@Test
	void keywordsIsAnEmptyArrayRatherThanNullWhenAiHasNotJudgedYet() throws Exception {
		long me = newMemberId();
		long recordId = createRecord(me, seed("rc-nokw"), "판정 전 기록");
		createCollection(me, "키워드 없는 책", List.of(recordId));

		JsonNode item = list(me, recordId, "").at("/data/items/0");

		assertThat(item.at("/keywords").isArray()).isTrue();
		assertThat(keywordsOf(item)).isEmpty();
	}

	private JsonNode list(long memberId, long recordId, String query) throws Exception {
		return parse(mockMvc.perform(
				get("/v1/records/" + recordId + "/collections" + query).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private List<Long> collectionIdsOf(JsonNode response) {
		List<Long> ids = new ArrayList<>();
		response.at("/data/items").forEach(item -> ids.add(item.at("/collectionId").asLong()));
		return ids;
	}

	/** {@code kakao_place_id}는 {@code VARCHAR(50)}이라 접두사와 난수를 짧게 유지한다. */
	private String seed(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
	}

	private static final String COVER_URL =
		"/image/files/3f2a9c1e-8d4b-4f6a-9c0e-5b7d2e8a1c44_image_0.webp";

	private List<String> keywordsOf(JsonNode item) {
		List<String> keywords = new ArrayList<>();
		item.at("/keywords").forEach(keyword -> keywords.add(keyword.asString()));
		return keywords;
	}

	private int insertPreset(String code, String displayName, String visibility) {
		Integer id = jdbcTemplate.queryForObject(
			"SELECT coalesce(max(id), 0) + 1 FROM ai.keyword_preset", Integer.class);
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, 'MOOD', '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, true, 1)
			""", id, code + "-" + UUID.randomUUID().toString().substring(0, 8), displayName, visibility);
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
}
