package com.pinlog.pinlogback.domain.follow;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import com.pinlog.pinlogback.support.CoreApiFixtures;

import tools.jackson.databind.JsonNode;

/**
 * 작성자 공개 책장 탐색(API 명세 8.1).
 *
 * <p>이 엔드포인트가 §9.3 {@code GET /follows/{followId}/collections}와 다른 점은 <b>진입 키</b>다.
 * 팔로우하지 않은 작성자가 대상이라 {@code followId}가 없고, 가진 것은 발견한 Collection의 id뿐이다
 * (06 §5.3 식별자 은닉). 그래서 이 테스트는 <b>팔로우 전 상태</b>를 주된 경로로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("작성자 공개 책장 탐색")
class ShelfApiTests extends CoreApiFixtures {

	@Test
	@DisplayName("공개 Collection의 id로 작성자의 다른 발행 Collection을 받는다")
	void strangerBrowsesAuthorShelf() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-entry", "성수 산책");
		long other = publishedCollection(author, "shelf-other", "연남 카페");

		JsonNode data = shelfOf(stranger, entry);

		assertThat(data.at("/sourceCollectionId").asLong()).isEqualTo(entry);
		assertThat(collectionIds(data)).containsExactlyInAnyOrder(entry, other);
	}

	@Test
	@DisplayName("다른 작성자의 Collection은 섞이지 않는다")
	void shelfContainsOnlyThatAuthorsCollections() throws Exception {
		long author = newMemberId();
		long another = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-mine", "내 것");
		long foreign = publishedCollection(another, "shelf-foreign", "남의 것");

		JsonNode data = shelfOf(stranger, entry);

		assertThat(collectionIds(data)).contains(entry).doesNotContain(foreign);
	}

	/**
	 * 필드 이름 집합을 단언한다. 작성자 id 값을 본문에서 문자열로 찾는 방식은 쓰지 않는다 —
	 * Collection id가 작성자 id의 자릿수를 포함하면(예: 작성자 3, Collection 13) 유출이 없어도
	 * 실패해서, 통과·실패가 구현이 아니라 시퀀스 값에 좌우된다.
	 */
	@Test
	@DisplayName("응답에 작성자의 내부 사용자 id가 없다")
	void shelfHidesAuthorIdentity() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-anon", "익명");

		JsonNode data = shelfOf(stranger, entry);

		assertThat(fieldNames(data)).containsExactlyInAnyOrder(
			"sourceCollectionId", "follow", "collections");
		assertThat(fieldNames(data.at("/follow"))).containsExactlyInAnyOrder(
			"followed", "followId", "alias");
		assertThat(fieldNames(data.at("/collections"))).containsExactlyInAnyOrder(
			"items", "nextCursor", "hasNext");
		assertThat(fieldNames(data.at("/collections/items/0"))).containsExactlyInAnyOrder(
			"collectionId", "title", "recordCount", "keywords", "createdAt");
	}

	@Test
	@DisplayName("존재하지 않는 collectionId는 404")
	void unknownEntryIsNotFound() throws Exception {
		long stranger = newMemberId();

		mockMvc.perform(get("/v1/collections/{collectionId}/shelf", 999_999_999L)
				.with(loginAs(stranger)))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("삭제된 Collection을 진입점으로 주면 404")
	void deletedEntryIsNotFound() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-deleted-entry", "지워짐");
		softDelete("core.collection", entry);

		mockMvc.perform(get("/v1/collections/{collectionId}/shelf", entry)
				.with(loginAs(stranger)))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("탈퇴한 작성자의 Collection을 진입점으로 주면 404")
	void withdrawnAuthorEntryIsNotFound() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-withdrawn", "탈퇴");
		softDelete("core.member", author);

		mockMvc.perform(get("/v1/collections/{collectionId}/shelf", entry)
				.with(loginAs(stranger)))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("목록에서 삭제된 Collection이 빠진다")
	void deletedCollectionsAreExcludedFromShelf() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-keep", "남는 것");
		long removed = publishedCollection(author, "shelf-remove", "빠지는 것");
		softDelete("core.collection", removed);

		JsonNode data = shelfOf(stranger, entry);

		assertThat(collectionIds(data)).contains(entry).doesNotContain(removed);
	}

	@Test
	@DisplayName("목록에서 미발행 Collection이 빠진다")
	void unpublishedCollectionsAreExcludedFromShelf() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-pub", "발행됨");
		long hidden = publishedCollection(author, "shelf-unpub", "미발행");
		jdbcTemplate.update("UPDATE core.collection SET is_published = false WHERE id = ?", hidden);

		JsonNode data = shelfOf(stranger, entry);

		assertThat(collectionIds(data)).contains(entry).doesNotContain(hidden);
	}

	@Test
	@DisplayName("아직 팔로우하지 않았으면 follow가 비어 있다")
	void notFollowedYetReportsUnfollowed() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-unfollowed", "팔로우 전");

		JsonNode data = shelfOf(stranger, entry);

		assertThat(data.at("/follow/followed").asBoolean()).isFalse();
		assertThat(data.at("/follow/followId").isNull()).isTrue();
		assertThat(data.at("/follow/alias").isNull()).isTrue();
	}

	@Test
	@DisplayName("이미 팔로우한 작성자면 followId와 별칭이 실린다")
	void alreadyFollowedReportsFollowIdAndAlias() throws Exception {
		long author = newMemberId();
		long follower = newMemberId();
		long entry = publishedCollection(author, "shelf-followed", "팔로우 후");
		long followId = follow(follower, entry);
		mockMvc.perform(patch("/v1/follows/{followId}", followId).with(loginAs(follower))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"alias\": \"성수 취향\"}"))
			.andExpect(status().isOk());

		JsonNode data = shelfOf(follower, entry);

		assertThat(data.at("/follow/followed").asBoolean()).isTrue();
		assertThat(data.at("/follow/followId").asLong()).isEqualTo(followId);
		assertThat(data.at("/follow/alias").asString()).isEqualTo("성수 취향");
	}

	@Test
	@DisplayName("다른 사람의 팔로우 관계가 내 응답에 새지 않는다")
	void followBlockIsPerViewer() throws Exception {
		long author = newMemberId();
		long follower = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-per-viewer", "관계별");
		follow(follower, entry);

		JsonNode data = shelfOf(stranger, entry);

		assertThat(data.at("/follow/followed").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("size가 범위를 벗어나면 400이 아니라 보정된다")
	void sizeOutOfRangeIsNormalized() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-size", "크기");

		for (String size : List.of("0", "-1", "1000")) {
			mockMvc.perform(get("/v1/collections/{collectionId}/shelf", entry)
					.param("size", size)
					.with(loginAs(stranger)))
				.andExpect(status().isOk());
		}
	}

	@Test
	@DisplayName("커서로 다음 페이지를 이어 받고 항목이 중복되지 않는다")
	void cursorWalksShelfWithoutDuplicates() throws Exception {
		long author = newMemberId();
		long stranger = newMemberId();
		long entry = publishedCollection(author, "shelf-page-1", "첫째");
		publishedCollection(author, "shelf-page-2", "둘째");
		publishedCollection(author, "shelf-page-3", "셋째");

		JsonNode first = parse(mockMvc.perform(get("/v1/collections/{collectionId}/shelf", entry)
				.param("size", "2")
				.with(loginAs(stranger)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");
		assertThat(collectionIds(first)).hasSize(2);
		assertThat(first.at("/collections/hasNext").asBoolean()).isTrue();

		String cursor = first.at("/collections/nextCursor").asString();
		JsonNode second = parse(mockMvc.perform(get("/v1/collections/{collectionId}/shelf", entry)
				.param("size", "2")
				.param("cursor", cursor)
				.with(loginAs(stranger)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");

		assertThat(collectionIds(second)).hasSize(1);
		assertThat(second.at("/collections/hasNext").asBoolean()).isFalse();
		assertThat(collectionIds(second)).doesNotContainAnyElementsOf(collectionIds(first));
	}

	/** 첫 페이지 응답의 {@code data} 노드. */
	private JsonNode shelfOf(long viewer, long collectionId) throws Exception {
		return parse(mockMvc.perform(get("/v1/collections/{collectionId}/shelf", collectionId)
				.with(loginAs(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andReturn().getResponse().getContentAsString()).at("/data");
	}

	private List<String> fieldNames(JsonNode node) {
		return node.propertyStream().map(java.util.Map.Entry::getKey).toList();
	}

	private List<Long> collectionIds(JsonNode data) {
		return data.at("/collections/items").valueStream()
			.map(item -> item.at("/collectionId").asLong())
			.toList();
	}

	/** Collection은 생성 시 자동 발행된다(BD-23). Record 하나를 만들어 묶는다. */
	private long publishedCollection(long memberId, String placeKey, String title) throws Exception {
		long recordId = createRecord(memberId, placeKey, "맥락 " + placeKey);
		return createCollection(memberId, title, List.of(recordId));
	}
}
