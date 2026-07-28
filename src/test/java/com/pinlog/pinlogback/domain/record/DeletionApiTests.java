package com.pinlog.pinlogback.domain.record;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
	"management.health.redis.enabled=false",
	"pinlog.auth.stub.enabled=true"
})
@AutoConfigureMockMvc
class DeletionApiTests extends IntegrationContainerSupport {

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextDeleteWithTwoActiveContextsSoftDeletesOnlyThatContext() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-ctx-1", "첫 번째");
		long secondContextId = addContext(memberId, recordId, "두 번째");

		mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, secondContextId)
				.with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(activeContextCount(recordId)).isEqualTo(1);
	}

	@Test
	void lastContextDeleteReturns409WithImpactAndChangesNothing() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-ctx-last-1", "유일한 맥락");
		long contextId = firstContextId(memberId, recordId);
		long collectionId = createCollection(memberId, "마지막인 컬렉션", List.of(recordId));

		mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(memberId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DELETE_CONFIRMATION_REQUIRED"))
			.andExpect(jsonPath("$.error.impact.recordDeleted").value(true))
			.andExpect(jsonPath("$.error.impact.collectionIds[0]").value(collectionId));

		assertThat(activeContextCount(recordId)).isEqualTo(1);
		assertThat(activeRecordExists(recordId)).isTrue();
	}

	@Test
	void forceDeleteRemovesRecordContextsAndLastCollections() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-force-1", "유일한 맥락");
		long lastCollection = createCollection(memberId, "함께 사라질 책", List.of(recordId));
		long otherRecord = createRecord(memberId, "del-force-2", "다른 기록");
		long survivingCollection = createCollection(memberId, "살아남을 책", List.of(recordId, otherRecord));

		mockMvc.perform(delete("/v1/records/{recordId}/force", recordId).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(activeRecordExists(recordId)).isFalse();
		assertThat(activeContextCount(recordId)).isZero();
		assertThat(activeCollectionExists(lastCollection)).isFalse();
		assertThat(activeCollectionExists(survivingCollection)).isTrue();
		assertThat(activeLinkCount(survivingCollection)).isEqualTo(1);
		assertThat(storedRecordCount(survivingCollection)).isEqualTo(1);
	}

	@Test
	void generalRecordDeleteIsRejectedWhenRecordIsLastInAnyCollection() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-rec-last-1", "맥락");
		long collectionId = createCollection(memberId, "마지막인 책", List.of(recordId));

		mockMvc.perform(delete("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DELETE_CONFIRMATION_REQUIRED"))
			.andExpect(jsonPath("$.error.impact.recordDeleted").value(true))
			.andExpect(jsonPath("$.error.impact.collectionIds[0]").value(collectionId));

		assertThat(activeRecordExists(recordId)).isTrue();
	}

	@Test
	void generalRecordDeleteSucceedsWhenCollectionsKeepOtherRecords() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-rec-ok-1", "맥락");
		long otherRecord = createRecord(memberId, "del-rec-ok-2", "다른 기록");
		long collectionId = createCollection(memberId, "유지될 책", List.of(recordId, otherRecord));

		mockMvc.perform(delete("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(activeRecordExists(recordId)).isFalse();
		assertThat(activeCollectionExists(collectionId)).isTrue();
		assertThat(activeLinkCount(collectionId)).isEqualTo(1);
		assertThat(storedRecordCount(collectionId)).isEqualTo(1);
	}

	@Test
	void removingRecordFromCollectionKeepsCollectionWhenOthersRemain() throws Exception {
		long memberId = newMemberId();
		long recordA = createRecord(memberId, "del-link-1a", "맥락");
		long recordB = createRecord(memberId, "del-link-1b", "맥락");
		long collectionId = createCollection(memberId, "두 권짜리", List.of(recordA, recordB));

		mockMvc.perform(delete("/v1/collections/{collectionId}/records/{recordId}", collectionId, recordA)
				.with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(activeRecordExists(recordA)).isTrue();
		assertThat(activeCollectionExists(collectionId)).isTrue();
		assertThat(activeLinkCount(collectionId)).isEqualTo(1);
		assertThat(storedRecordCount(collectionId)).isEqualTo(1);
	}

	@Test
	void removingLastRecordFromCollectionIs409() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-link-last-1", "맥락");
		long collectionId = createCollection(memberId, "한 권짜리", List.of(recordId));

		mockMvc.perform(delete("/v1/collections/{collectionId}/records/{recordId}", collectionId, recordId)
				.with(loginAs(memberId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DELETE_CONFIRMATION_REQUIRED"))
			.andExpect(jsonPath("$.error.impact.recordDeleted").value(false))
			.andExpect(jsonPath("$.error.impact.collectionIds[0]").value(collectionId));

		assertThat(activeLinkCount(collectionId)).isEqualTo(1);
	}

	@Test
	void deletingCollectionKeepsOriginalRecordsActive() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-col-1", "맥락");
		long collectionId = createCollection(memberId, "지울 책", List.of(recordId));

		mockMvc.perform(delete("/v1/collections/{collectionId}", collectionId).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(activeCollectionExists(collectionId)).isFalse();
		assertThat(activeLinkCount(collectionId)).isZero();
		assertThat(activeRecordExists(recordId)).isTrue();

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isOk());
	}

	@Test
	void reSavingDeletedPlaceCreatesFreshRecordWithoutRestoringLinks() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-resave-1", "옛 맥락");
		long otherRecord = createRecord(memberId, "del-resave-2", "다른 기록");
		long collectionId = createCollection(memberId, "옛 연결", List.of(recordId, otherRecord));

		mockMvc.perform(delete("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		JsonNode recreated = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(recordBody("del-resave-1", "새 맥락")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.result").value("RECORD_CREATED"))
			.andExpect(jsonPath("$.data.contexts.length()").value(1))
			.andExpect(jsonPath("$.data.contexts[0].body").value("새 맥락"))
			.andReturn().getResponse().getContentAsString());

		long newRecordId = recreated.at("/data/recordId").asLong();
		assertThat(newRecordId).isNotEqualTo(recordId);

		long linksOfNewRecord = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.collection_record WHERE record_id = ? AND deleted_at IS NULL",
			Long.class, newRecordId);
		assertThat(linksOfNewRecord).isZero();
		assertThat(activeLinkCount(collectionId)).isEqualTo(1);
	}

	@Test
	void concurrentContextDeletesNeverLeaveZeroActiveContexts() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "del-race-1", "첫 번째");
		long firstContextId = firstContextId(memberId, recordId);
		long secondContextId = addContext(memberId, recordId, "두 번째");

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> results = List.of(firstContextId, secondContextId).stream()
				.map(contextId -> executor.submit(() -> {
					ready.countDown();
					start.await();
					return mockMvc.perform(
							delete("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
								.with(loginAs(memberId)))
						.andReturn().getResponse().getStatus();
				}))
				.collect(Collectors.toList());
			ready.await();
			start.countDown();

			List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
			assertThat(statuses).containsExactlyInAnyOrder(204, 409);
		} finally {
			executor.shutdownNow();
		}

		assertThat(activeContextCount(recordId)).isEqualTo(1);
	}

	@Test
	void strangersDeletesAreHiddenAs404() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long recordId = createRecord(owner, "del-hidden-1", "맥락");
		long contextId = firstContextId(owner, recordId);
		long collectionId = createCollection(owner, "남의 책", List.of(recordId));

		mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(stranger)))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/v1/records/{recordId}", recordId).with(loginAs(stranger)))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/v1/records/{recordId}/force", recordId).with(loginAs(stranger)))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/v1/collections/{collectionId}", collectionId).with(loginAs(stranger)))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/v1/collections/{collectionId}/records/{recordId}", collectionId, recordId)
				.with(loginAs(stranger)))
			.andExpect(status().isNotFound());
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private String recordBody(String kakaoPlaceId, String contextBody) {
		return """
			{
			\t"place": {
			\t\t"kakaoPlaceId": "%s",
			\t\t"name": "장소",
			\t\t"address": "주소",
			\t\t"lat": 37.5,
			\t\t"lng": 127.0
			\t},
			\t"contextBody": "%s"
			}
			""".formatted(kakaoPlaceId, contextBody);
	}

	private long createRecord(long memberId, String kakaoPlaceId, String contextBody) throws Exception {
		JsonNode response = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(recordBody(kakaoPlaceId, contextBody)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/recordId").asLong();
	}

	private long firstContextId(long memberId, long recordId) throws Exception {
		JsonNode detail = parse(mockMvc.perform(get("/v1/records/{recordId}", recordId)
				.with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		return detail.at("/data/contexts/0/contextId").asLong();
	}

	private long addContext(long memberId, long recordId, String body) throws Exception {
		JsonNode response = parse(mockMvc.perform(
				post("/v1/records/{recordId}/contexts", recordId).with(loginAs(memberId))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\": \"" + body + "\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/contextId").asLong();
	}

	private long createCollection(long memberId, String title, List<Long> recordIds) throws Exception {
		String ids = recordIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		JsonNode response = parse(mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"" + title + "\", \"recordIds\": [" + ids + "]}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/collectionId").asLong();
	}

	private long activeContextCount(long recordId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.context WHERE record_id = ? AND deleted_at IS NULL",
			Long.class, recordId);
	}

	private boolean activeRecordExists(long recordId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.record WHERE id = ? AND deleted_at IS NULL",
			Long.class, recordId) > 0;
	}

	private boolean activeCollectionExists(long collectionId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.collection WHERE id = ? AND deleted_at IS NULL",
			Long.class, collectionId) > 0;
	}

	private long activeLinkCount(long collectionId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.collection_record WHERE collection_id = ? AND deleted_at IS NULL",
			Long.class, collectionId);
	}

	private long storedRecordCount(long collectionId) {
		return jdbcTemplate.queryForObject(
			"SELECT record_count FROM core.collection WHERE id = ?", Long.class, collectionId);
	}

	private JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
