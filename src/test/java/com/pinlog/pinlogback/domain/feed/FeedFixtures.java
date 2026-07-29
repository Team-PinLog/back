package com.pinlog.pinlogback.domain.feed;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feed 통합 테스트의 공통 Fixture.
 *
 * <p>Core 데이터는 <b>실제 API로</b> 만든다 — 직접 INSERT하면 {@code record_count} 같은 비정규화
 * 값이 실제 경로와 어긋나 Feed가 보는 상태가 운영과 달라진다. 반면 {@code ai} 스키마는 FastAPI가
 * 채우는 영역이라 백엔드에 쓰기 경로가 없으므로 JDBC로 직접 넣는다.
 *
 * <p>테스트 간 DB를 비우지 않는다(공유 컨테이너). 그래서 단언은 "응답 전체"가 아니라 <b>내가 만든
 * id로 걸러낸 부분</b>에 건다 — 다른 테스트 클래스가 만든 Collection도 후보에 들어오기 때문이다.
 */
abstract class FeedFixtures extends IntegrationContainerSupport {

	protected static final String FEED_URL = "/v1/feed/collections";

	protected final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected MemberRepository memberRepository;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	protected long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	/** Record 하나를 담은 공개 Collection. Feed 후보의 최소 단위다. */
	protected long publishedCollection(long ownerId, String seed) throws Exception {
		return createCollection(ownerId, "책 " + seed.substring(Math.max(0, seed.length() - 8)),
			List.of(createRecord(ownerId, seed)));
	}

	protected long createRecord(long memberId, String kakaoPlaceId) throws Exception {
		String body = """
			{
			\t"place": {
			\t\t"kakaoPlaceId": "%s",
			\t\t"name": "장소",
			\t\t"address": "주소",
			\t\t"lat": 37.5,
			\t\t"lng": 127.0
			\t},
			\t"contextBody": "피드 후보용 맥락"
			}
			""".formatted(kakaoPlaceId);
		JsonNode response = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/recordId").asLong();
	}

	protected long createCollection(long memberId, String title, List<Long> recordIds) throws Exception {
		String ids = recordIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		JsonNode response = parse(mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"" + title + "\", \"recordIds\": [" + ids + "]}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/collectionId").asLong();
	}

	protected void follow(long followerId, long collectionId) throws Exception {
		mockMvc.perform(post("/v1/follows").with(loginAs(followerId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andExpect(status().isCreated());
	}

	/**
	 * Keyword Preset을 하나 만든다. {@code embedding}은 {@code NOT NULL}이지만 Feed는 벡터를 쓰지
	 * 않으므로 0 벡터로 채운다 — 이 테스트가 임베딩에 의존하지 않는다는 사실 자체가 경계의 증거다.
	 *
	 * @param visibility {@code PUBLIC} · {@code PRIVATE_ONLY} · {@code BLOCKED}
	 */
	protected int insertPreset(String code, String visibility, boolean active) {
		Integer id = jdbcTemplate.queryForObject(
			"SELECT coalesce(max(id), 0) + 1 FROM ai.keyword_preset", Integer.class);
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, 'MOOD', '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, ?, 1)
			""", id, code, code, visibility, active);
		return id;
	}

	/** Record의 모든 활성 Context에 Keyword를 붙인다. FastAPI가 채우는 자리를 대신한다. */
	protected void attachKeyword(long recordId, int presetId) {
		List<Long> contextIds = jdbcTemplate.queryForList(
			"SELECT id FROM core.context WHERE record_id = ? AND deleted_at IS NULL", Long.class, recordId);
		contextIds.forEach(contextId -> jdbcTemplate.update("""
			INSERT INTO ai.context_keyword (context_id, keyword_id, confidence, preset_version)
			VALUES (?, ?, 0.900, 1)
			""", contextId, presetId));
	}

	/** {@code kakao_place_id}는 {@code VARCHAR(50)}이므로 접두사와 난수를 짧게 유지한다. */
	protected String uniqueSeed(String prefix) {
		return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 12);
	}

	protected JsonNode feed(long memberId, String query) throws Exception {
		return parse(mockMvc.perform(get(FEED_URL + query).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	protected List<Long> collectionIdsOf(JsonNode response) {
		List<Long> ids = new java.util.ArrayList<>();
		response.at("/data/items").forEach(item -> ids.add(item.at("/collectionId").asLong()));
		return ids;
	}

	protected JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
