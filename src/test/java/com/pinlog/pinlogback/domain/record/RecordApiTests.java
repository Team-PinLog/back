package com.pinlog.pinlogback.domain.record;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@SpringBootTest(properties = {
	"management.health.redis.enabled=false",
	"pinlog.auth.stub.enabled=true"
})
@AutoConfigureMockMvc
class RecordApiTests extends PostgresContainerSupport {

	@Autowired
	private MockMvc mockMvc;

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void createRecordReturns201WithFirstContextAndEmptyKeywords() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("api-create-1", "비 오는 날 가려고 저장")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.result").value("RECORD_CREATED"))
			.andExpect(jsonPath("$.data.recordId").isNumber())
			.andExpect(jsonPath("$.data.place.placeId").isNumber())
			.andExpect(jsonPath("$.data.place.name").value("앤트러사이트 성수"))
			.andExpect(jsonPath("$.data.contexts.length()").value(1))
			.andExpect(jsonPath("$.data.contexts[0].body").value("비 오는 날 가려고 저장"))
			.andExpect(jsonPath("$.data.keywords").isEmpty())
			.andExpect(jsonPath("$.data.createdAt").exists());
	}

	@Test
	void createRecordReusesExistingPlaceSnapshotWithoutUpdating() throws Exception {
		long memberId = newMemberId();
		Place existing = placeRepository.save(Place.create(
			"api-reuse-1", "원래 이름", "원래 주소", null, null, null,
			new BigDecimal("37.1000000"), new BigDecimal("127.1000000")));

		mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("api-reuse-1", "저장 이유")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.place.placeId").value(existing.getId()))
			.andExpect(jsonPath("$.data.place.name").value("원래 이름"));

		long placeRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.place WHERE kakao_place_id = 'api-reuse-1'", Long.class);
		assertThat(placeRows).isEqualTo(1);
	}

	@Test
	void secondCreateForSamePlaceAddsContextAndReturns200() throws Exception {
		long memberId = newMemberId();

		JsonNode first = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("api-second-1", "첫 저장 이유")))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());

		mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("api-second-1", "두 번째 이유")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.result").value("CONTEXT_ADDED"))
			.andExpect(jsonPath("$.data.recordId").value(first.at("/data/recordId").asLong()))
			.andExpect(jsonPath("$.data.contexts.length()").value(2));
	}

	@Test
	void recordDetailReturnsContextsOldestFirst() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-detail-1", "첫 번째");
		addContext(memberId, recordId, "두 번째");

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recordId").value(recordId))
			.andExpect(jsonPath("$.data.contexts[0].body").value("첫 번째"))
			.andExpect(jsonPath("$.data.contexts[1].body").value("두 번째"))
			.andExpect(jsonPath("$.data.keywords").isEmpty());
	}

	@Test
	void otherUsersRecordDetailIsHiddenAs404() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long recordId = createRecord(owner, "api-hidden-1", "저장 이유");

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(stranger)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void byPlaceReturnsNullRecordWhenAbsent() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(get("/v1/records/by-place").with(loginAs(memberId))
				.param("kakaoPlaceId", "api-absent-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.record").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void byPlaceReturnsMyActiveRecordWhenPresent() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-present-1", "저장 이유");

		mockMvc.perform(get("/v1/records/by-place").with(loginAs(memberId))
				.param("kakaoPlaceId", "api-present-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.record.recordId").value(recordId))
			.andExpect(jsonPath("$.data.record.contexts.length()").value(1));
	}

	@Test
	void addContextReturns201AndTouchesRecordUpdatedAt() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-touch-1", "첫 번째");
		Instant before = recordUpdatedAt(recordId);

		mockMvc.perform(post("/v1/records/{recordId}/contexts", recordId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"실제로 방문했다\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.contextId").isNumber())
			.andExpect(jsonPath("$.data.body").value("실제로 방문했다"))
			.andExpect(jsonPath("$.data.keywords").isEmpty());

		assertThat(recordUpdatedAt(recordId)).isAfter(before);
	}

	@Test
	void editContextReturnsNewIdAndInheritsOriginCreatedAt() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-edit-1", "처음 이유");
		JsonNode detail = detail(memberId, recordId);
		long oldContextId = detail.at("/data/contexts/0/contextId").asLong();
		String originCreatedAt = detail.at("/data/contexts/0/createdAt").asText();

		JsonNode edited = parse(mockMvc.perform(
				patch("/v1/records/{recordId}/contexts/{contextId}", recordId, oldContextId)
					.with(loginAs(memberId))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\": \"고친 이유\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.body").value("고친 이유"))
			.andReturn().getResponse().getContentAsString());

		assertThat(edited.at("/data/contextId").asLong()).isNotEqualTo(oldContextId);
		assertThat(edited.at("/data/createdAt").asText()).isEqualTo(originCreatedAt);

		JsonNode after = detail(memberId, recordId);
		assertThat(after.at("/data/contexts").size()).isEqualTo(1);
		assertThat(after.at("/data/contexts/0/contextId").asLong())
			.isEqualTo(edited.at("/data/contextId").asLong());
	}

	@Test
	void editWorksEvenWhenOnlyOneActiveContextExists() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-edit-single-1", "유일한 맥락");
		long contextId = detail(memberId, recordId).at("/data/contexts/0/contextId").asLong();

		mockMvc.perform(patch("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"수정된 유일한 맥락\"}"))
			.andExpect(status().isOk());

		long activeContexts = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.context WHERE record_id = ? AND deleted_at IS NULL",
			Long.class, recordId);
		assertThat(activeContexts).isEqualTo(1);
	}

	@Test
	void otherUsersContextEditIsHiddenAs404() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long recordId = createRecord(owner, "api-edit-hidden-1", "저장 이유");
		long contextId = detail(owner, recordId).at("/data/contexts/0/contextId").asLong();

		mockMvc.perform(patch("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(stranger))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"남의 것 수정 시도\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void requestWithoutAuthenticationIs401() throws Exception {
		mockMvc.perform(get("/v1/records/by-place").param("kakaoPlaceId", "any"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void blankContextBodyIs400() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("api-blank-1", "   ")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	@Test
	void outOfRangeLatitudeIs400() throws Exception {
		long memberId = newMemberId();
		String body = """
			{
			  "place": {
			    "kakaoPlaceId": "api-badlat-1",
			    "name": "이상한 장소",
			    "address": "주소",
			    "lat": 95.0,
			    "lng": 127.0
			  },
			  "contextBody": "저장 이유"
			}
			""";

		mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private String createBody(String kakaoPlaceId, String contextBody) {
		return """
			{
			  "place": {
			    "kakaoPlaceId": "%s",
			    "name": "앤트러사이트 성수",
			    "address": "성동구 성수동2가 273-1",
			    "roadAddress": "성동구 연무장길 47",
			    "phone": "02-1234-5678",
			    "placeUrl": "http://place.map.kakao.com/1234567",
			    "lat": 37.5447,
			    "lng": 127.0557
			  },
			  "contextBody": "%s"
			}
			""".formatted(kakaoPlaceId, contextBody);
	}

	private long createRecord(long memberId, String kakaoPlaceId, String contextBody) throws Exception {
		JsonNode response = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(kakaoPlaceId, contextBody)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/recordId").asLong();
	}

	private void addContext(long memberId, long recordId, String body) throws Exception {
		mockMvc.perform(post("/v1/records/{recordId}/contexts", recordId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"" + body + "\"}"))
			.andExpect(status().isCreated());
	}

	private JsonNode detail(long memberId, long recordId) throws Exception {
		return parse(mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private Instant recordUpdatedAt(long recordId) {
		return jdbcTemplate.queryForObject(
			"SELECT updated_at FROM core.record WHERE id = ?",
			java.time.OffsetDateTime.class, recordId).toInstant();
	}

	private JsonNode parse(String json) throws Exception {
		return jsonMapper.readTree(json);
	}
}
