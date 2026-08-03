package com.pinlog.pinlogback.domain.collection;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CollectionApiTests extends IntegrationContainerSupport {

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private ContextRepository contextRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void createCollectionReturns201WithAutoPublishAndRecordCount() throws Exception {
		long memberId = newMemberId();
		long recordA = newRecord(memberId, "col-create-1a");
		long recordB = newRecord(memberId, "col-create-1b");

		mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("비 오는 날의 카페", List.of(recordA, recordB))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.collectionId").isNumber())
			.andExpect(jsonPath("$.data.title").value("비 오는 날의 카페"))
			.andExpect(jsonPath("$.data.recordCount").value(2))
			.andExpect(jsonPath("$.data.publishedAt").exists())
			.andExpect(jsonPath("$.data.createdAt").exists());
	}

	@Test
	void createWithZeroRecordsIs400() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("빈 컬렉션", List.of())))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	@Test
	void createWithBlankOrTooLongTitleIs400() throws Exception {
		long memberId = newMemberId();
		long recordId = newRecord(memberId, "col-title-1");

		mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("   ", List.of(recordId))))
			.andExpect(status().isBadRequest());

		mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("가".repeat(21), List.of(recordId))))
			.andExpect(status().isBadRequest());
	}

	/**
	 * recordIds는 Collection 행을 잠근 상태에서 건당 INSERT를 돌기 때문에, 상한이 없으면 큰 배열
	 * 하나가 그 Collection의 다른 요청을 오래 막는다. 소유권 검사보다 먼저 걸러져야 하므로
	 * 존재하지 않는 id로도 404가 아니라 400이다.
	 */
	@Test
	void createWithMoreThan100RecordIdsIs400() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("너무 많은 기록", sequentialIds(101))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("recordIds"));
	}

	@Test
	void createWithExactly100RecordIdsSucceeds() throws Exception {
		long memberId = newMemberId();
		List<Long> recordIds = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			recordIds.add(newRecord(memberId, "col-limit-" + i));
		}

		mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("경계값", recordIds)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.recordCount").value(100));
	}

	@Test
	void addRecordsWithMoreThan100RecordIdsIs400() throws Exception {
		long memberId = newMemberId();
		long collectionId = createCollection(memberId, "추가 상한", List.of(newRecord(memberId, "col-add-limit-1")));

		mockMvc.perform(post("/v1/collections/{id}/records", collectionId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordIds\": [" + sequentialIds(101).stream()
					.map(String::valueOf).collect(Collectors.joining(", ")) + "]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("recordIds"));
	}

	@Test
	void createWithSomeoneElsesRecordIsHiddenAs404() throws Exception {
		long me = newMemberId();
		long other = newMemberId();
		long mine = newRecord(me, "col-others-1");
		long notMine = newRecord(other, "col-others-2");

		mockMvc.perform(post("/v1/collections").with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("남의 기록 섞임", List.of(mine, notMine))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void myCollectionsArePaginatedByCursorNewestFirst() throws Exception {
		long memberId = newMemberId();
		long first = createCollection(memberId, "첫 번째", List.of(newRecord(memberId, "col-page-1")));
		long second = createCollection(memberId, "두 번째", List.of(newRecord(memberId, "col-page-2")));
		long third = createCollection(memberId, "세 번째", List.of(newRecord(memberId, "col-page-3")));

		JsonNode page1 = parse(mockMvc.perform(get("/v1/collections").with(loginAs(memberId))
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andReturn().getResponse().getContentAsString());

		assertThat(page1.at("/data/items/0/collectionId").asLong()).isEqualTo(third);
		assertThat(page1.at("/data/items/1/collectionId").asLong()).isEqualTo(second);

		String cursor = page1.at("/data/nextCursor").asText();
		JsonNode page2 = parse(mockMvc.perform(get("/v1/collections").with(loginAs(memberId))
				.param("size", "2").param("cursor", cursor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andReturn().getResponse().getContentAsString());

		assertThat(page2.at("/data/items/0/collectionId").asLong()).isEqualTo(first);
	}

	@Test
	void detailReturnsRecordsNewestAddedFirstWithCursorAndContexts() throws Exception {
		long memberId = newMemberId();
		long recordA = newRecord(memberId, "col-detail-1a");
		long collectionId = createCollection(memberId, "상세", List.of(recordA));
		long recordB = newRecord(memberId, "col-detail-1b");
		addRecords(memberId, collectionId, List.of(recordB));

		JsonNode page1 = parse(mockMvc.perform(get("/v1/collections/{id}", collectionId)
				.with(loginAs(memberId)).param("recordSize", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ownedByMe").value(true))
			.andExpect(jsonPath("$.data.records.items.length()").value(1))
			.andExpect(jsonPath("$.data.records.hasNext").value(true))
			.andReturn().getResponse().getContentAsString());

		assertThat(page1.at("/data/records/items/0/recordId").asLong()).isEqualTo(recordB);
		assertThat(page1.at("/data/records/items/0/addedToCollectionAt").asText()).isNotEmpty();
		assertThat(page1.at("/data/records/items/0/contexts/0/body").asText()).isNotEmpty();

		String cursor = page1.at("/data/records/nextCursor").asText();
		JsonNode page2 = parse(mockMvc.perform(get("/v1/collections/{id}", collectionId)
				.with(loginAs(memberId)).param("recordSize", "1").param("recordCursor", cursor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.records.hasNext").value(false))
			.andReturn().getResponse().getContentAsString());

		assertThat(page2.at("/data/records/items/0/recordId").asLong()).isEqualTo(recordA);
	}

	@Test
	void ownerCanRenameAndStrangerCannot() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long collectionId = createCollection(owner, "옛 제목", List.of(newRecord(owner, "col-rename-1")));

		mockMvc.perform(patch("/v1/collections/{id}", collectionId).with(loginAs(owner))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"새 제목\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.title").value("새 제목"));

		mockMvc.perform(patch("/v1/collections/{id}", collectionId).with(loginAs(stranger))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"탈취 시도\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void addRecordsSkipsDuplicatesIdempotentlyAndUpdatesCount() throws Exception {
		long memberId = newMemberId();
		long recordA = newRecord(memberId, "col-add-1a");
		long collectionId = createCollection(memberId, "멱등 추가", List.of(recordA));
		long recordB = newRecord(memberId, "col-add-1b");

		mockMvc.perform(post("/v1/collections/{id}/records", collectionId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordIds\": [" + recordA + ", " + recordB + "]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recordCount").value(2));

		long linkRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.collection_record"
				+ " WHERE collection_id = ? AND deleted_at IS NULL", Long.class, collectionId);
		assertThat(linkRows).isEqualTo(2);

		long storedCount = jdbcTemplate.queryForObject(
			"SELECT record_count FROM core.collection WHERE id = ?", Long.class, collectionId);
		assertThat(storedCount).isEqualTo(2);
	}

	@Test
	void addRecordsByStrangerIsHiddenAs404() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long collectionId = createCollection(owner, "남의 컬렉션", List.of(newRecord(owner, "col-add-2a")));
		long strangersRecord = newRecord(stranger, "col-add-2b");

		mockMvc.perform(post("/v1/collections/{id}/records", collectionId).with(loginAs(stranger))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordIds\": [" + strangersRecord + "]}"))
			.andExpect(status().isNotFound());
	}

	/**
	 * {@code CollectionService.requireAllOwnedActiveRecords}가 addRecords 경로에서도 강제되는지
	 * 고정한다. 이 검사가 뚫리면 남의 Record가 내 Collection에 링크되고,
	 * {@code CollectionRecordRepository.findByCollectionIdIn}의 javadoc이 근거로 삼는
	 * "Collection은 소유자 자기 Record만 담는다"는 전제가 깨져 탈퇴 연쇄 삭제(6.9)가
	 * 그 Record를 남의 Collection에 남긴다.
	 */
	@Test
	void addRecordsWithSomeoneElsesRecordIsHiddenAs404() throws Exception {
		long owner = newMemberId();
		long other = newMemberId();
		long collectionId = createCollection(owner, "본인 컬렉션", List.of(newRecord(owner, "col-add-3a")));
		long mine = newRecord(owner, "col-add-3b");
		long notMine = newRecord(other, "col-add-3c");

		mockMvc.perform(post("/v1/collections/{id}/records", collectionId).with(loginAs(owner))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordIds\": [" + mine + ", " + notMine + "]}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

		long linkRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.collection_record"
				+ " WHERE collection_id = ? AND deleted_at IS NULL", Long.class, collectionId);
		assertThat(linkRows).isEqualTo(1);
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long newRecord(long memberId, String kakaoPlaceId) {
		Place place = placeRepository.save(Place.create(
			kakaoPlaceId, "장소", "주소", null, null, null,
			new BigDecimal("37.5000000"), new BigDecimal("127.0000000")));
		Record record = recordRepository.save(Record.create(memberId, place.getId()));
		contextRepository.save(Context.create(record.getId(), memberId, "저장 이유"));
		return record.getId();
	}

	/** 검증이 소유권 검사보다 먼저 걸러지는지 보려면 실제로 존재하지 않는 id면 충분하다. */
	private List<Long> sequentialIds(int count) {
		return LongStream.rangeClosed(1, count).boxed().toList();
	}

	private String createBody(String title, List<Long> recordIds) {
		String ids = recordIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		return "{\"title\": \"" + title + "\", \"recordIds\": [" + ids + "]}";
	}

	private long createCollection(long memberId, String title, List<Long> recordIds) throws Exception {
		JsonNode response = parse(mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(title, recordIds)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/collectionId").asLong();
	}

	private void addRecords(long memberId, long collectionId, List<Long> recordIds) throws Exception {
		String ids = recordIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		mockMvc.perform(post("/v1/collections/{id}/records", collectionId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordIds\": [" + ids + "]}"))
			.andExpect(status().isOk());
	}

	private JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
