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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class RecordApiTests extends IntegrationContainerSupport {

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

	/**
	 * Place가 이미 커밋돼 있으면 Place upsert의 ON CONFLICT가 블로킹하지 않아 두 트랜잭션이 나란히
	 * "내 활성 Record 없음"으로 판정한다. 순차 요청(위 테스트)과 같은 결과로 수렴해야 한다.
	 */
	@Test
	void concurrentCreateOnExistingPlaceAddsContextInsteadOfFailing() throws Exception {
		createRecord(newMemberId(), "api-race-1", "다른 회원이 먼저 저장해 Place를 만든다");
		long memberId = newMemberId();

		List<RawResponse> responses = runConcurrently(
			() -> postRecord(memberId, "api-race-1", "동시 요청 A"),
			() -> postRecord(memberId, "api-race-1", "동시 요청 B"));

		assertThat(responses).extracting(RawResponse::status).containsExactlyInAnyOrder(201, 200);

		JsonNode created = parse(bodyOf(responses, 201));
		JsonNode added = parse(bodyOf(responses, 200));
		assertThat(added.at("/data/result").asText()).isEqualTo("CONTEXT_ADDED");
		assertThat(added.at("/data/recordId").asLong()).isEqualTo(created.at("/data/recordId").asLong());

		// 경합에 진 요청의 본문도 남아야 한다 — 재조회만 하고 돌려주면 조용히 사라진다.
		assertThat(activeContextBodies(created.at("/data/recordId").asLong()))
			.containsExactlyInAnyOrder("동시 요청 A", "동시 요청 B");
	}

	/**
	 * Place가 없을 때는 upsert의 ON CONFLICT가 미커밋 키에서 블로킹해 두 트랜잭션이 직렬화된다.
	 * 이미 통과하는 경로이며, 위 수정이 이 동작을 깨지 않는지 지키는 회귀 테스트다.
	 */
	@Test
	void concurrentCreateOnNewPlaceStaysSerialized() throws Exception {
		long memberId = newMemberId();

		List<RawResponse> responses = runConcurrently(
			() -> postRecord(memberId, "api-race-new-1", "동시 요청 A"),
			() -> postRecord(memberId, "api-race-new-1", "동시 요청 B"));

		assertThat(responses).extracting(RawResponse::status).containsExactlyInAnyOrder(201, 200);
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

	/**
	 * place 썸네일은 시연용 목업 단계라 SQL로만 채워진다(S15P11A705-305). 생성 경로는 값을 넣지 않으므로
	 * 기본은 null이고, null이어도 필드를 생략하지 않는다 — 프론트 폴백 분기가 명시적이도록(명세 11.1).
	 */
	@Test
	void recordDetailIncludesPlaceThumbnailUrlWhenFilled() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-thumb-1", "저장 이유");
		jdbcTemplate.update(
			"UPDATE core.place SET thumbnail_url = '/api/core/images/places/cafe-1.jpg' "
				+ "WHERE kakao_place_id = 'api-thumb-1'");

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.place.thumbnailUrl").value("/api/core/images/places/cafe-1.jpg"));
	}

	@Test
	void recordDetailReturnsNullThumbnailUrlByDefault() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-thumb-2", "저장 이유");

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.place").hasJsonPath())
			.andExpect(jsonPath("$.data.place.thumbnailUrl").value(org.hamcrest.Matchers.nullValue()));
	}

	/** contextSort=CREATED_AT_DESC로 최신순을 지원한다(명세 5.2, S15P11A705-265). 기준은 최초 작성 시각이다(BD-25). */
	@Test
	void recordDetailSupportsNewestFirstContexts() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-ctxsort-1", "첫 번째");
		addContext(memberId, recordId, "두 번째");

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId))
				.param("contextSort", "CREATED_AT_DESC"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.contexts[0].body").value("두 번째"))
			.andExpect(jsonPath("$.data.contexts[1].body").value("첫 번째"));

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId))
				.param("contextSort", "CREATED_AT_ASC"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.contexts[0].body").value("첫 번째"));
	}

	@Test
	void undefinedContextSortValueIs400InvalidInput() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-ctxsort-2", "저장 이유");

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId))
				.param("contextSort", "NEWEST"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
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

	/**
	 * Context 본문은 그대로 임베딩 입력이 되어 호출 비용과 직결된다(데이터모델 8장). 본문이 들어오는
	 * 경로가 셋이므로 세 곳 모두 막아야 한다 — 한 곳만 열려 있으면 상한을 우회할 수 있다.
	 */
	@Test
	void contextBodyLongerThan500CharsIs400OnEveryEntryPoint() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-len-1", "첫 맥락");
		long contextId = detail(memberId, recordId).at("/data/contexts/0/contextId").asLong();
		String tooLong = "가".repeat(501);

		mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("api-len-2", tooLong)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("contextBody"));

		mockMvc.perform(post("/v1/records/{recordId}/contexts", recordId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"" + tooLong + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("body"));

		mockMvc.perform(patch("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"" + tooLong + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("body"));
	}

	@Test
	void contextBodyOfExactly500CharsSucceeds() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "api-len-ok-1", "첫 맥락");
		String atLimit = "가".repeat(500);

		mockMvc.perform(post("/v1/records/{recordId}/contexts", recordId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"" + atLimit + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.body").value(atLimit));
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

	private record RawResponse(int status, String body) {
	}

	private RawResponse postRecord(long memberId, String kakaoPlaceId, String contextBody) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(kakaoPlaceId, contextBody)))
			.andReturn().getResponse();
		return new RawResponse(response.getStatus(), response.getContentAsString());
	}

	/**
	 * 두 요청을 같은 순간에 출발시킨다. 어느 쪽이 이기는지는 보장하지 않으므로 단정은 순서에
	 * 의존하지 않는다.
	 */
	private List<RawResponse> runConcurrently(Callable<RawResponse> first, Callable<RawResponse> second)
		throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<RawResponse>> futures = List.of(first, second).stream()
				.map(call -> executor.submit(() -> {
					ready.countDown();
					start.await();
					return call.call();
				}))
				.toList();
			ready.await();
			start.countDown();
			return List.of(futures.get(0).get(), futures.get(1).get());
		} finally {
			executor.shutdownNow();
		}
	}

	private String bodyOf(List<RawResponse> responses, int status) {
		return responses.stream()
			.filter(response -> response.status() == status)
			.map(RawResponse::body)
			.findFirst()
			.orElseThrow();
	}

	private List<String> activeContextBodies(long recordId) {
		return jdbcTemplate.queryForList(
			"SELECT body FROM core.context WHERE record_id = ? AND deleted_at IS NULL",
			String.class, recordId);
	}

	private JsonNode parse(String json) throws Exception {
		return jsonMapper.readTree(json);
	}
}
