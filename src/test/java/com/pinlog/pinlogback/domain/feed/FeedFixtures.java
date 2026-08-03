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
import com.pinlog.pinlogback.global.response.CursorPage;
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

	/**
	 * 축을 신경 쓰지 않는 테스트가 쓰는 category. 프리셋의 실제 네 축 중 하나를 골라 둔다 —
	 * 없는 값을 넣으면 축 정렬을 검증하는 테스트가 현실과 다른 전제 위에서 돌게 된다.
	 */
	private static final String DEFAULT_CATEGORY = "ATMOSPHERE";

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
	 * Keyword Preset을 하나 만든다. 표시값을 따로 신경 쓰지 않는 테스트용이라 {@code display_name}을
	 * {@code code}와 같게 둔다 — <b>둘을 구분해야 하는 테스트는 4인자 오버로드를 쓴다</b>.
	 *
	 * @param visibility {@code PUBLIC} · {@code PRIVATE_ONLY} · {@code BLOCKED}
	 */
	protected int insertPreset(String code, String visibility, boolean active) {
		return insertPreset(code, code, visibility, active);
	}

	/**
	 * {@code code}와 {@code display_name}이 다른 Preset. 응답이 어느 쪽을 실었는지 가리려면 두 값이
	 * 달라야 한다 — 같게 두면 {@code code}를 내보내는 구현도 통과한다(S15P11A705-252).
	 *
	 * <p>{@code embedding}은 {@code NOT NULL}이지만 Feed는 벡터를 쓰지 않으므로 0 벡터로 채운다 —
	 * 이 테스트가 임베딩에 의존하지 않는다는 사실 자체가 경계의 증거다.
	 */
	protected int insertPreset(String code, String displayName, String visibility, boolean active) {
		return insertPreset(code, displayName, DEFAULT_CATEGORY, visibility, active);
	}

	/**
	 * category까지 정하는 Preset. 표시 Keyword 정렬의 1순위가 <b>축(category) 내 순위</b>이므로
	 * (P46, feed-recommendation 3.7.1) 축을 가리는 테스트는 이 오버로드를 쓴다.
	 *
	 * <p>{@code id}는 {@code max(id) + 1}이라 <b>삽입 순서가 곧 id 순서</b>다 — 동점 규칙이
	 * {@code preset.id} 오름차순이므로 테스트가 기대 순서를 삽입 순서로 적을 수 있다.
	 *
	 * <p>{@code embedding}은 {@code NOT NULL}이지만 Feed는 벡터를 쓰지 않으므로 0 벡터로 채운다 —
	 * 이 테스트가 임베딩에 의존하지 않는다는 사실 자체가 경계의 증거다.
	 *
	 * @param category {@code COMPANION}(누구와) · {@code ACTIVITY}(무엇을) ·
	 *     {@code ATMOSPHERE}(어떤 분위기) · {@code SITUATION}(어떤 상황)
	 */
	protected int insertPreset(String code, String displayName, String category, String visibility,
		boolean active) {
		Integer id = jdbcTemplate.queryForObject(
			"SELECT coalesce(max(id), 0) + 1 FROM ai.keyword_preset", Integer.class);
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, ?, '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, ?, 1)
			""", id, code, displayName, category, visibility, active);
		return id;
	}

	/** 영문 대문자 {@code code}. 한글 표시값과 문자 집합이 겹치지 않아 부분 문자열 오탐이 없다. */
	protected String uniqueCode(String prefix) {
		return prefix + "_" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
	}

	/** 한글 표시값. {@link #uniqueCode}가 만든 어떤 code와도 부분 문자열로 겹치지 않는다. */
	protected String uniqueDisplayName(String prefix) {
		return prefix + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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

	/**
	 * 한 페이지에 최대한 담아 응답 <b>원문</b>을 돌려준다. 파싱한 트리가 아니라 원문이어야 "이
	 * 문자열이 응답 어디에도 없다"를 단언할 수 있다 — 필드를 하나 빠뜨려도 걸린다.
	 */
	protected String feedPayload(long memberId) throws Exception {
		return mockMvc.perform(get(FEED_URL)
				.param("size", String.valueOf(CursorPage.MAX_SIZE)).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
	}

	/** 공유 컨테이너라 다른 테스트가 만든 항목도 섞인다. 내가 만든 id로만 골라낸다. */
	protected JsonNode itemOf(JsonNode response, long collectionId) {
		for (JsonNode item : response.at("/data/items")) {
			if (item.at("/collectionId").asLong() == collectionId) {
				return item;
			}
		}
		return null;
	}

	protected List<String> keywordsOf(JsonNode item) {
		List<String> keywords = new java.util.ArrayList<>();
		item.at("/keywords").forEach(keyword -> keywords.add(keyword.asString()));
		return keywords;
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
