package com.pinlog.pinlogback.domain.follow;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.support.CoreApiFixtures;

import tools.jackson.databind.JsonNode;

/**
 * {@code GET /v1/follows}의 {@code collectionSize} 확장(API 명세 9.2, S15P11A705-244).
 * 주면 각 팔로우 항목에 그 책장 Collection의 <b>첫 페이지</b>가 실리고, 주지 않으면
 * 기존 응답이 그대로다 — 두 계약을 모두 여기서 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FollowListCollectionsApiTests extends CoreApiFixtures {

	@Autowired
	private CollectionRepository collectionRepository;

	@Test
	void collectionSizeAttachesEachShelfsOwnFirstPage() throws Exception {
		long me = newMemberId();
		long ownerA = newMemberId();
		long a1 = shelfCollection(ownerA, "A 첫째");
		long a2 = shelfCollection(ownerA, "A 둘째");
		long a3 = shelfCollection(ownerA, "A 셋째");
		long ownerB = newMemberId();
		long b1 = shelfCollection(ownerB, "B 하나");
		long followA = follow(me, a1);
		long followB = follow(me, b1);

		JsonNode data = listWithCollections(me, "2");

		// 팔로우 축은 기존 9.2 그대로 최신순이다.
		assertThat(data.at("/items/0/followId").asLong()).isEqualTo(followB);
		assertThat(data.at("/items/1/followId").asLong()).isEqualTo(followA);

		// 책장마다 자기 Collection만, 오래된순(기본) 첫 페이지만 담긴다(S15P11A705-265).
		JsonNode shelfB = data.at("/items/0/collections");
		assertThat(collectionIds(shelfB)).containsExactly(b1);
		assertThat(shelfB.at("/hasNext").asBoolean()).isFalse();
		assertThat(shelfB.at("/nextCursor").isNull()).isTrue();

		JsonNode shelfA = data.at("/items/1/collections");
		assertThat(collectionIds(shelfA)).containsExactly(a1, a2);
		assertThat(shelfA.at("/hasNext").asBoolean()).isTrue();
		assertThat(shelfA.at("/nextCursor").asText()).isNotBlank();

		// 항목 형태는 9.3과 같다.
		JsonNode first = shelfA.at("/items/0");
		assertThat(first.at("/title").asText()).isEqualTo("A 첫째");
		assertThat(first.at("/recordCount").asInt()).isZero();
		assertThat(first.at("/keywords").isArray()).isTrue();
		assertThat(first.at("/createdAt").asText()).isNotBlank();
	}

	/** 기존 호출 보존 — {@code collectionSize}가 없으면 {@code collections} 필드 자체가 없다. */
	@Test
	void withoutCollectionSizeResponseKeepsItsCurrentShape() throws Exception {
		long me = newMemberId();
		follow(me, shelfCollection(newMemberId(), "책장"));

		mockMvc.perform(get("/v1/follows").with(loginAs(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].followId").isNumber())
			.andExpect(jsonPath("$.data.items[0].alias").hasJsonPath())
			.andExpect(jsonPath("$.data.items[0].createdAt").exists())
			.andExpect(jsonPath("$.data.items[0].collections").doesNotExist());
	}

	/**
	 * 팔로우 뒤 Collection이 전부 사라진 책장. 팔로우 축에서는 항목으로 남고
	 * {@code collections.items}만 빈 배열이어야 한다(명세 9.2).
	 */
	@Test
	void shelfWhoseCollectionsAllDisappearedStillAppearsWithEmptyItems() throws Exception {
		long me = newMemberId();
		long only = shelfCollection(newMemberId(), "곧 삭제");
		long followId = follow(me, only);
		softDelete("core.collection", only);

		JsonNode data = listWithCollections(me, "3");

		assertThat(data.at("/items/0/followId").asLong()).isEqualTo(followId);
		JsonNode collections = data.at("/items/0/collections");
		assertThat(collections.at("/items").isEmpty()).isTrue();
		assertThat(collections.at("/hasNext").asBoolean()).isFalse();
		assertThat(collections.at("/nextCursor").isNull()).isTrue();
	}

	/**
	 * 안쪽 커서는 새 계약이 아니다 — 기존 9.3 엔드포인트에 그대로 넣으면
	 * {@code collectionSize} 다음 항목부터 이어지고 중복이 없다. 기본 방향은 오래된순이다.
	 */
	@Test
	void innerNextCursorContinuesOnTheExistingEndpointWithoutDuplicates() throws Exception {
		long me = newMemberId();
		long owner = newMemberId();
		long c1 = shelfCollection(owner, "첫째");
		long c2 = shelfCollection(owner, "둘째");
		long c3 = shelfCollection(owner, "셋째");
		long followId = follow(me, c1);

		JsonNode shelf = listWithCollections(me, "2").at("/items/0/collections");
		assertThat(collectionIds(shelf)).containsExactly(c1, c2);

		JsonNode rest = parse(mockMvc.perform(
				get("/v1/follows/{followId}/collections", followId).with(loginAs(me))
					.param("cursor", shelf.at("/nextCursor").asText()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");

		assertThat(collectionIds(rest)).containsExactly(c3);
		assertThat(rest.at("/hasNext").asBoolean()).isFalse();
	}

	/**
	 * {@code collectionSort}로 동봉 페이지를 최신순으로 받으면, 이어받는 9.3 호출도 같은
	 * {@code sort}를 줘야 커서가 이어진다 — 동봉 페이지와 9.3은 한 커서 계약이다(S15P11A705-265).
	 */
	@Test
	void collectionSortFlipsEmbeddedPagesAndChainsWithSameDirection() throws Exception {
		long me = newMemberId();
		long owner = newMemberId();
		long c1 = shelfCollection(owner, "첫째");
		long c2 = shelfCollection(owner, "둘째");
		long c3 = shelfCollection(owner, "셋째");
		long followId = follow(me, c1);

		JsonNode shelf = parse(mockMvc.perform(get("/v1/follows").with(loginAs(me))
				.param("collectionSize", "2").param("collectionSort", "CREATED_AT_DESC"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data/items/0/collections");
		assertThat(collectionIds(shelf)).containsExactly(c3, c2);

		JsonNode rest = parse(mockMvc.perform(
				get("/v1/follows/{followId}/collections", followId).with(loginAs(me))
					.param("sort", "CREATED_AT_DESC")
					.param("cursor", shelf.at("/nextCursor").asText()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");

		assertThat(collectionIds(rest)).containsExactly(c1);
		assertThat(rest.at("/hasNext").asBoolean()).isFalse();
	}

	/**
	 * 노출 조건은 기존 9.3 경로와 같다 — 미발행·소프트 삭제 Collection은 항목에서도,
	 * {@code hasNext} 판정에서도 빠져야 한다. 숨은 행이 hasNext만 부풀리면 클라이언트가
	 * 빈 다음 페이지를 부르게 된다.
	 */
	@Test
	void unpublishedAndDeletedCollectionsAreExcludedFromTheFirstPage() throws Exception {
		long me = newMemberId();
		long owner = newMemberId();
		long visible = shelfCollection(owner, "보이는 책");
		long unpublished = shelfCollection(owner, "미발행 책");
		jdbcTemplate.update("UPDATE core.collection SET is_published = false WHERE id = ?", unpublished);
		long deleted = shelfCollection(owner, "삭제된 책");
		softDelete("core.collection", deleted);
		follow(me, visible);

		JsonNode shelf = listWithCollections(me, "1").at("/items/0/collections");

		assertThat(collectionIds(shelf)).containsExactly(visible);
		assertThat(shelf.at("/hasNext").asBoolean()).isFalse();
	}

	/** {@code keywords}는 기존 경로와 같은 집계다 — 한글 표시값이며 {@code PUBLIC}만 나간다(BD-13·BD-18). */
	@Test
	void keywordsAreKoreanPublicOnlyLikeTheExistingPath() throws Exception {
		long me = newMemberId();
		long owner = newMemberId();
		long recordId = createRecord(owner, uniquePlace("follow-agg"), "키워드 맥락");
		attachKeyword(recordId, insertPreset(uniqueCode("AGG_PUB"), "산책길", "PUBLIC"));
		attachKeyword(recordId, insertPreset(uniqueCode("AGG_PRV"), "집 근처", "PRIVATE_ONLY"));
		completeKeywords(recordId);
		long collectionId = createCollection(owner, "키워드 책장", List.of(recordId));
		follow(me, collectionId);

		JsonNode shelf = listWithCollections(me, "3").at("/items/0/collections");

		assertThat(collectionIds(shelf)).containsExactly(collectionId);
		List<String> keywords = new ArrayList<>();
		shelf.at("/items/0/keywords").forEach(node -> keywords.add(node.asText()));
		assertThat(keywords).contains("산책길").doesNotContain("집 근처");
	}

	/** 범위 밖 값은 400이 아니라 보정이다(명세 9.2) — {@code size}와 같은 규칙을 탄다. */
	@Test
	void zeroAndOversizedValuesAreNormalizedNot400() throws Exception {
		long me = newMemberId();
		follow(me, shelfCollection(newMemberId(), "책장"));

		mockMvc.perform(get("/v1/follows").with(loginAs(me)).param("collectionSize", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].collections.items").isArray());

		mockMvc.perform(get("/v1/follows").with(loginAs(me)).param("collectionSize", "1000"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].collections.items").isArray());

		mockMvc.perform(get("/v1/follows").with(loginAs(me))
				.param("size", "0").param("collectionSize", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].collections.items").isArray());
	}

	// ── fixtures ────────────────────────────────────────────────────────────

	private long shelfCollection(long ownerId, String title) {
		return collectionRepository.save(Collection.create(ownerId, title)).getId();
	}

	private JsonNode listWithCollections(long memberId, String collectionSize) throws Exception {
		return parse(mockMvc.perform(get("/v1/follows").with(loginAs(memberId))
				.param("collectionSize", collectionSize))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");
	}

	private List<Long> collectionIds(JsonNode cursorPage) {
		List<Long> ids = new ArrayList<>();
		cursorPage.at("/items").forEach(item -> ids.add(item.at("/collectionId").asLong()));
		return ids;
	}

	/** FastAPI가 채울 자리를 대신한다 — {@code KeywordExposureApiTests}와 같은 선택. */
	private int insertPreset(String code, String displayName, String visibility) {
		Integer id = jdbcTemplate.queryForObject(
			"SELECT coalesce(max(id), 0) + 1 FROM ai.keyword_preset", Integer.class);
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, 'MOOD', '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, true, 1)
			""", id, code, displayName, visibility);
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
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** 공유 컨테이너라 코드가 유일해야 한다 — 다른 테스트 클래스의 프리셋과 충돌하지 않도록. */
	private String uniqueCode(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}
}
