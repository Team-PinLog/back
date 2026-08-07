package com.pinlog.pinlogback.domain.record;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * {@code GET /v1/records/map/keywords}의 계약(S15P11A705-388).
 *
 * <p>이 클래스가 지키는 것은 두 가지다. 하나는 <b>감춰야 할 것이 새지 않는가</b> —
 * {@code BLOCKED}·비활성 프리셋과 판정이 끝나지 않은 Context는 집계에 들어오면 안 된다(BD-13).
 * 다른 하나는 <b>칩의 숫자가 지도의 핀 수와 같은 규칙으로 세지는가</b>다. Keyword는 Context에
 * 붙지만 이 응답은 Record 단위 집계이므로, 같은 Record 안의 중복은 반드시 접혀야 한다.
 *
 * <p>Core 데이터는 리포지토리로 직접 만든다. {@code POST /v1/records}를 쓰면 커밋 이후 리스너가
 * AI 서버를 부르는데 이 테스트에는 대역이 없다 — 검증 대상도 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordMapKeywordsApiTests extends IntegrationContainerSupport {

	private static final String URL = "/v1/records/map/keywords";

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
	void topKeywordsComeBackOrderedByRecordCountDescending() throws Exception {
		long memberId = newMemberId();
		int cafe = insertPreset("카페", "PUBLIC", true);
		int park = insertPreset("공원", "PUBLIC", true);
		attachTo(newRecordInside(memberId, "kw-order-1"), memberId, cafe);
		attachTo(newRecordInside(memberId, "kw-order-2"), memberId, cafe);
		attachTo(newRecordInside(memberId, "kw-order-3"), memberId, cafe);
		attachTo(newRecordInside(memberId, "kw-order-4"), memberId, park);

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].keywordId").value(cafe))
			.andExpect(jsonPath("$.data.items[0].displayName").value("카페"))
			.andExpect(jsonPath("$.data.items[0].recordCount").value(3))
			.andExpect(jsonPath("$.data.items[1].keywordId").value(park))
			.andExpect(jsonPath("$.data.items[1].recordCount").value(1));
	}

	@Test
	void blockedPresetIsExcluded() throws Exception {
		long memberId = newMemberId();
		int blocked = insertPreset("가려진것", "BLOCKED", true);
		attachTo(newRecordInside(memberId, "kw-blocked-1"), memberId, blocked);

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void inactivePresetIsExcluded() throws Exception {
		long memberId = newMemberId();
		int retired = insertPreset("폐기된것", "PUBLIC", false);
		attachTo(newRecordInside(memberId, "kw-inactive-1"), memberId, retired);

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void privateOnlyPresetIsIncluded() throws Exception {
		long memberId = newMemberId();
		int privateOnly = insertPreset("나만보기", "PRIVATE_ONLY", true);
		attachTo(newRecordInside(memberId, "kw-private-1"), memberId, privateOnly);

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].displayName").value("나만보기"));
	}

	@Test
	void contextsWhoseKeywordJudgementIsNotCompletedAreExcluded() throws Exception {
		long memberId = newMemberId();
		int preset = insertPreset("판정중", "PUBLIC", true);
		long recordId = newRecordInside(memberId, "kw-status-1");
		long contextId = newContext(recordId, memberId);
		attachKeyword(contextId, preset);
		putState(contextId, "PROCESSING");

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void sameKeywordOnSeveralContextsOfOneRecordCountsOnce() throws Exception {
		long memberId = newMemberId();
		int preset = insertPreset("카페", "PUBLIC", true);
		long recordId = newRecordInside(memberId, "kw-dedup-1");
		attachKeyword(newContext(recordId, memberId), preset);
		attachKeyword(newContext(recordId, memberId), preset);
		attachKeyword(newContext(recordId, memberId), preset);

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordCount").value(1));
	}

	@Test
	void tiesAreBrokenByKeywordIdAscending() throws Exception {
		long memberId = newMemberId();
		int first = insertPreset("힣하나", "PUBLIC", true);
		int second = insertPreset("가둘", "PUBLIC", true);
		attachTo(newRecordInside(memberId, "kw-tie-1"), memberId, first);
		attachTo(newRecordInside(memberId, "kw-tie-2"), memberId, second);

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].keywordId").value(first))
			.andExpect(jsonPath("$.data.items[1].keywordId").value(second));
	}

	@Test
	void atMostFiveKeywordsComeBack() throws Exception {
		long memberId = newMemberId();
		for (int i = 0; i < 7; i++) {
			int preset = insertPreset("키워드" + i, "PUBLIC", true);
			attachTo(newRecordInside(memberId, "kw-limit-" + i), memberId, preset);
		}

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(5));
	}

	@Test
	void recordsOutsideTheBoxAreExcluded() throws Exception {
		long memberId = newMemberId();
		int inside = insertPreset("안쪽", "PUBLIC", true);
		int outside = insertPreset("바깥쪽", "PUBLIC", true);
		attachTo(newRecordInside(memberId, "kw-bbox-in"), memberId, inside);
		attachTo(newRecord(memberId, "kw-bbox-out", "35.0000000", "129.0000000"), memberId, outside);

		getInBounds(memberId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].displayName").value("안쪽"));
	}

	@Test
	void omittingTheWholeBboxAggregatesEverything() throws Exception {
		long memberId = newMemberId();
		int inside = insertPreset("안쪽", "PUBLIC", true);
		int outside = insertPreset("바깥쪽", "PUBLIC", true);
		attachTo(newRecordInside(memberId, "kw-nobbox-in"), memberId, inside);
		attachTo(newRecord(memberId, "kw-nobbox-out", "35.0000000", "129.0000000"), memberId, outside);

		mockMvc.perform(get(URL).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2));
	}

	@Test
	void partialBboxIs400() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(get(URL).with(loginAs(memberId)).param("swLat", "37.4"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	private ResultActions getInBounds(long memberId) throws Exception {
		return mockMvc.perform(get(URL).with(loginAs(memberId))
			.param("swLat", "37.4").param("swLng", "126.9")
			.param("neLat", "37.6").param("neLng", "127.1"));
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	/** bbox(37.4~37.6, 126.9~127.1) 안쪽 좌표에 Record 하나를 만든다. */
	private long newRecordInside(long memberId, String kakaoPlaceId) {
		return newRecord(memberId, kakaoPlaceId, "37.5000000", "127.0000000");
	}

	private long newRecord(long memberId, String kakaoPlaceId, String lat, String lng) {
		Place place = placeRepository.save(Place.create(
			kakaoPlaceId, "장소 " + kakaoPlaceId, "주소", null, null, null,
			new BigDecimal(lat), new BigDecimal(lng)));
		return recordRepository.save(Record.create(memberId, place.getId())).getId();
	}

	private long newContext(long recordId, long memberId) {
		return contextRepository.save(Context.create(recordId, memberId, "본문 " + recordId)).getId();
	}

	/** Record 하나에 Context 하나를 만들고 Keyword를 붙인다. */
	private void attachTo(long recordId, long memberId, int presetId) {
		attachKeyword(newContext(recordId, memberId), presetId);
	}

	/** FastAPI가 채우는 자리를 대신한다. {@code keyword_status = COMPLETED}가 조회 판정의 전제다. */
	private void attachKeyword(long contextId, int presetId) {
		jdbcTemplate.update("""
			INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status)
			VALUES (?, 'COMPLETED', 'COMPLETED')
			ON CONFLICT (context_id) DO UPDATE SET keyword_status = 'COMPLETED'
			""", contextId);
		jdbcTemplate.update("""
			INSERT INTO ai.context_keyword (context_id, keyword_id, confidence, preset_version)
			VALUES (?, ?, 0.900, 1)
			""", contextId, presetId);
	}

	/** {@link #attachKeyword}는 COMPLETED로 고정하므로 미완료 상태는 이 메서드로 덮는다. */
	private void putState(long contextId, String keywordStatus) {
		jdbcTemplate.update("""
			INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status)
			VALUES (?, 'COMPLETED', ?)
			ON CONFLICT (context_id) DO UPDATE SET keyword_status = excluded.keyword_status
			""", contextId, keywordStatus);
	}

	private int insertPreset(String displayName, String visibility, boolean active) {
		Integer id = jdbcTemplate.queryForObject(
			"SELECT coalesce(max(id), 0) + 1 FROM ai.keyword_preset", Integer.class);
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, 'MOOD', '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, ?, 1)
			""", id, "CHIP_" + id, displayName, visibility, active);
		return id;
	}
}
