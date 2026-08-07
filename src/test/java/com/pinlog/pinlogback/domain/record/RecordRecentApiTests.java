package com.pinlog.pinlogback.domain.record;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /v1/records/recent} 계약(API 명세 5.9). 홈 화면 "최근 기록" 영역이 쓰는 조회다.
 *
 * <p>7일 창을 확인하려면 과거 시각의 Record가 있어야 한다. 생성 API는 항상 {@code now()}를 찍고
 * {@code created_at}은 JPA에서 {@code updatable = false}라 엔티티로도 못 바꾸므로, 네이티브
 * UPDATE로 시각을 옮긴다({@code FeedEventApiTests}가 {@code feed_event}에 쓰는 것과 같은 수).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordRecentApiTests extends IntegrationContainerSupport {

	private static final String RECENT_URL = "/v1/records/recent";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Test
	void returnsOneCardWithPlaceAndKeywordsByDefault() throws Exception {
		long me = newMemberId();
		long recordId = createRecord(me, "recent-default", "비 오는 날 가려고 저장");

		mockMvc.perform(get(RECENT_URL).with(loginAs(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(recordId))
			.andExpect(jsonPath("$.data.items[0].place.name").value("앤트러사이트 성수"))
			.andExpect(jsonPath("$.data.items[0].keywords").isArray())
			.andExpect(jsonPath("$.data.items[0].createdAt").exists())
			.andExpect(jsonPath("$.data.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	/** 카드에 Context 본문을 담을 자리 자체가 없다 — 필요하면 5.2를 따로 부른다. */
	@Test
	void cardCarriesNoContextBody() throws Exception {
		long me = newMemberId();
		createRecord(me, "recent-no-context", "본문이 새면 안 된다");

		mockMvc.perform(get(RECENT_URL).with(loginAs(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].contexts").doesNotExist());
	}

	@Test
	void excludesRecordsOlderThanSevenDays() throws Exception {
		long me = newMemberId();
		long inWindow = createRecord(me, "recent-in", "엿새 전 기록");
		long outOfWindow = createRecord(me, "recent-out", "여드레 전 기록");
		backdate(inWindow, 6);
		backdate(outOfWindow, 8);

		JsonNode page = recent(me, null, 10);

		assertThat(recordIds(page)).containsExactly(inWindow).doesNotContain(outOfWindow);
	}

	@Test
	void ordersByCreatedAtDescending() throws Exception {
		long me = newMemberId();
		long oldest = createRecord(me, "recent-order-1", "가장 오래된");
		long middle = createRecord(me, "recent-order-2", "가운데");
		long newest = createRecord(me, "recent-order-3", "가장 최근");
		backdate(oldest, 5);
		backdate(middle, 3);
		backdate(newest, 1);

		JsonNode page = recent(me, null, 10);

		assertThat(recordIds(page)).containsExactly(newest, middle, oldest);
	}

	/** {@code created_at}이 같으면 id 내림차순으로 끊는다 — 없으면 페이지 경계에서 항목이 흔들린다. */
	@Test
	void breaksTiesOnTheSameCreatedAtByIdDescending() throws Exception {
		long me = newMemberId();
		long first = createRecord(me, "recent-tie-1", "동시각 하나");
		long second = createRecord(me, "recent-tie-2", "동시각 둘");
		jdbcTemplate.update(
			"UPDATE core.record SET created_at = now() - INTERVAL '1 day' WHERE id IN (?, ?)", first, second);

		JsonNode page = recent(me, null, 10);

		assertThat(recordIds(page)).containsExactly(second, first);
	}

	@Test
	void cursorWalksEveryRecordExactlyOnce() throws Exception {
		long me = newMemberId();
		List<Long> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long recordId = createRecord(me, "recent-walk-" + i, "기록 " + i);
			backdate(recordId, 5 - i);
			created.add(recordId);
		}

		List<Long> walked = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = recent(me, cursor, null);
			assertThat(page.at("/items").size()).isEqualTo(1);
			walked.addAll(recordIds(page));
			cursor = page.at("/nextCursor").isNull() ? null : page.at("/nextCursor").asString();
		} while (cursor != null);

		assertThat(walked).containsExactlyElementsOf(created.reversed());
	}

	/**
	 * 진짜 커서가 통하는 것을 먼저 보인 뒤 위조를 거절시킨다. 위조 단정만 두면 이 검증은 <b>매핑이
	 * 없어도 통과한다</b> — 매핑이 없으면 이 경로가 {@code GET /v1/records/{recordId}}에 걸려
	 * {@code "recent"}를 {@code Long}으로 바꾸려다 실패하는데, 그 오류도 {@code INVALID_INPUT} 400이라
	 * 위조 커서의 거절과 응답이 구분되지 않는다.
	 */
	@Test
	void rejectsForgedCursor() throws Exception {
		long me = newMemberId();
		createRecord(me, "recent-forged-1", "첫째");
		createRecord(me, "recent-forged-2", "둘째");
		String realCursor = recent(me, null, null).at("/nextCursor").asString();

		mockMvc.perform(get(RECENT_URL).param("cursor", realCursor).with(loginAs(me)))
			.andExpect(status().isOk());

		mockMvc.perform(get(RECENT_URL).param("cursor", "not-a-real-cursor").with(loginAs(me)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	@Test
	void foldsSizeBelowOneUpToOne() throws Exception {
		long me = newMemberId();
		createRecord(me, "recent-size-lo-1", "하나");
		createRecord(me, "recent-size-lo-2", "둘");

		JsonNode page = recent(me, null, 0);

		assertThat(page.at("/items").size()).isEqualTo(1);
	}

	@Test
	void foldsSizeAboveTheMaximumDownToOneHundred() throws Exception {
		long me = newMemberId();
		saveRecords(me, 101);

		JsonNode page = recent(me, null, 150);

		assertThat(page.at("/items").size()).isEqualTo(100);
		assertThat(page.at("/hasNext").asBoolean()).isTrue();
	}

	@Test
	void excludesOtherMembersRecords() throws Exception {
		long me = newMemberId();
		long stranger = newMemberId();
		long mine = createRecord(me, "recent-mine", "내 기록");
		long theirs = createRecord(stranger, "recent-theirs", "남의 기록");

		JsonNode page = recent(me, null, 10);

		assertThat(recordIds(page)).containsExactly(mine).doesNotContain(theirs);
	}

	@Test
	void excludesSoftDeletedRecords() throws Exception {
		long me = newMemberId();
		long kept = createRecord(me, "recent-kept", "남는 기록");
		long removed = createRecord(me, "recent-removed", "지운 기록");
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/v1/records/{recordId}/force", removed).with(loginAs(me)))
			.andExpect(status().isNoContent());

		JsonNode page = recent(me, null, 10);

		assertThat(recordIds(page)).containsExactly(kept).doesNotContain(removed);
	}

	@Test
	void returnsEmptyPageWhenNothingIsInTheWindow() throws Exception {
		long me = newMemberId();
		long stale = createRecord(me, "recent-empty", "여드레 전 기록뿐");
		backdate(stale, 8);

		mockMvc.perform(get(RECENT_URL).with(loginAs(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	/** 소유자 범위는 {@code PUBLIC + PRIVATE_ONLY}다. {@code BLOCKED}는 어느 범위에도 없다(BD-18). */
	@Test
	void exposesOwnerScopedKeywords() throws Exception {
		long me = newMemberId();
		long recordId = createRecord(me, "recent-keywords", "키워드가 붙는 기록");
		attachKeyword(recordId, insertPreset("REC_PUB", "공개키워드", "PUBLIC"));
		attachKeyword(recordId, insertPreset("REC_PRV", "비공개키워드", "PRIVATE_ONLY"));
		attachKeyword(recordId, insertPreset("REC_BLK", "차단키워드", "BLOCKED"));
		completeKeywords(recordId);

		JsonNode page = recent(me, null, 10);

		List<String> keywords = new ArrayList<>();
		page.at("/items/0/keywords").forEach(node -> keywords.add(node.asString()));
		assertThat(keywords).containsExactlyInAnyOrder("공개키워드", "비공개키워드");
	}

	@Test
	void keywordsIsAnEmptyArrayRatherThanNullWhenAiHasNotJudgedYet() throws Exception {
		long me = newMemberId();
		createRecord(me, "recent-no-keyword", "AI 판정 전");

		mockMvc.perform(get(RECENT_URL).with(loginAs(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywords").isArray())
			.andExpect(jsonPath("$.data.items[0].keywords").isEmpty());
	}

	// ── fixtures ────────────────────────────────────────────────────────────

	private JsonNode recent(long memberId, String cursor, Integer size) throws Exception {
		var request = get(RECENT_URL).with(loginAs(memberId));
		if (cursor != null) {
			request = request.param("cursor", cursor);
		}
		if (size != null) {
			request = request.param("size", String.valueOf(size));
		}
		return parse(mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).at("/data");
	}

	private List<Long> recordIds(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.at("/items").forEach(item -> ids.add(item.at("/recordId").asLong()));
		return ids;
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long createRecord(long memberId, String kakaoPlaceId, String contextBody) throws Exception {
		String body = """
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
			""".formatted(unique(kakaoPlaceId), contextBody);
		JsonNode response = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/recordId").asLong();
	}

	/**
	 * 상한 검증만을 위해 101건이 필요하다. 생성 API를 101번 부르면 Context·AI 상태까지 함께 만들어
	 * 느리므로, 이 검증에 필요한 Record와 Place만 직접 저장한다.
	 */
	private void saveRecords(long memberId, int count) {
		for (int i = 0; i < count; i++) {
			Place place = placeRepository.save(Place.create(
				unique("recent-bulk-" + i), "장소 " + i, "주소 " + i, null, null, null,
				new BigDecimal("37.5000000"), new BigDecimal("127.0000000")));
			recordRepository.save(Record.create(memberId, place.getId()));
		}
	}

	private void backdate(long recordId, int days) {
		jdbcTemplate.update(
			"UPDATE core.record SET created_at = now() - make_interval(days => ?) WHERE id = ?",
			days, recordId);
	}

	/** FastAPI가 채울 자리를 대신한다({@code KeywordExposureApiTests}와 같은 선택). */
	private int insertPreset(String code, String displayName, String visibility) {
		Integer id = jdbcTemplate.queryForObject(
			"SELECT coalesce(max(id), 0) + 1 FROM ai.keyword_preset", Integer.class);
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, 'MOOD', '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, true, 1)
			""", id, unique(code), displayName, visibility);
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

	private String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
