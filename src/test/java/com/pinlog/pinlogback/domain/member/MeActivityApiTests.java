package com.pinlog.pinlogback.domain.member;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.pinlog.pinlogback.support.CoreApiFixtures;

import tools.jackson.databind.JsonNode;

/**
 * 나의 활동 기록 집계(S15P11A705-397). 기간은 전체 누적이며 기간 파라미터가 없다.
 *
 * <p><b>이 테스트가 고정해야 하는 것은 시간대다.</b> {@code record.created_at}은
 * {@code TIMESTAMPTZ}라 {@code date_trunc}를 그냥 쓰면 서버 타임존을 탄다. 한국 서비스에서
 * "5월 17일에 3곳"은 KST 기준이어야 하므로, 경계에 걸친 시각(KST 자정 직후 = UTC 전날 오후)을
 * 일부러 넣어 UTC로 끊으면 틀리는 자리를 만든다.
 *
 * <p>{@code created_at}은 DB {@code now()}가 채우므로 API로는 과거를 만들 수 없다. 만든 뒤
 * {@code UPDATE}로 옮긴다 — {@code place.address}도 같은 이유다({@link CoreApiFixtures}의
 * {@code createRecord}가 주소를 "주소"로 고정한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("나의 활동 기록 집계")
class MeActivityApiTests extends CoreApiFixtures {

	private static final String PATH = "/v1/me/activity";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Test
	@DisplayName("다섯 덩어리를 한 응답으로 반환한다")
	void returnsFiveBlocks() throws Exception {
		long memberId = newMemberId();
		givenRecord(memberId, "act-1", "성수 앤트러사이트", "서울 성동구 성수이로 87", "2026-01-14T10:00");

		JsonNode data = getActivity(memberId).at("/data");

		assertThat(data.propertyNames())
			.containsExactlyInAnyOrder("totals", "months", "areas", "counts", "highlights");
	}

	@Test
	@DisplayName("totals가 장소 수·자치구 수·첫 기록일을 담는다")
	void totalsCountPlacesAndDistricts() throws Exception {
		long memberId = newMemberId();
		givenRecord(memberId, "act-t1", "연남 커피리브레", "서울 마포구 성미산로 198", "2026-03-02T10:00");
		givenRecord(memberId, "act-t2", "망원 한강공원", "서울 마포구 마포나루길 467", "2026-04-05T10:00");
		givenRecord(memberId, "act-t3", "서촌 대오서점", "서울 종로구 자하문로 7길", "2026-02-11T10:00");

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totals.placeCount").value(3))
			// 마포구·종로구 둘. 같은 구의 장소 둘은 하나로 센다.
			.andExpect(jsonPath("$.data.totals.districtCount").value(2))
			.andExpect(jsonPath("$.data.totals.firstRecordedOn").value("2026-02-11"));
	}

	@Test
	@DisplayName("months가 첫 기록 달부터 이번 달까지 이어지고 빈 달은 0으로 채워진다")
	void monthsAreContiguousAndZeroFilled() throws Exception {
		long memberId = newMemberId();
		// 3월과 5월에만 기록한다 — 4월이 0으로 채워져야 한다.
		givenRecord(memberId, "act-m1", "장소A", "서울 마포구 A로 1", "2026-03-10T10:00");
		givenRecord(memberId, "act-m2", "장소B", "서울 마포구 B로 2", "2026-05-20T10:00");
		givenRecord(memberId, "act-m3", "장소C", "서울 마포구 C로 3", "2026-05-21T10:00");

		JsonNode months = getActivity(memberId).at("/data/months");

		assertThat(months.get(0).get("month").asText()).isEqualTo("2026-03");
		assertThat(months.get(0).get("recordCount").asInt()).isEqualTo(1);
		assertThat(months.get(1).get("month").asText()).isEqualTo("2026-04");
		assertThat(months.get(1).get("recordCount").asInt()).isZero();
		assertThat(months.get(2).get("month").asText()).isEqualTo("2026-05");
		assertThat(months.get(2).get("recordCount").asInt()).isEqualTo(2);

		// 마지막 칸은 항상 이번 달이다 — 기록이 없어도 이어진다.
		String thisMonth = YearMonth.now(KST).toString();
		assertThat(months.get(months.size() - 1).get("month").asText()).isEqualTo(thisMonth);
		// 3월부터 이번 달까지 빠짐없이 이어진다.
		assertThat(months.size())
			.isEqualTo((int) YearMonth.parse("2026-03").until(YearMonth.now(KST), ChronoUnit.MONTHS) + 1);
	}

	@Test
	@DisplayName("areas가 건수 내림차순 상위 5개이고 동점은 지역명 오름차순이다")
	void areasAreTopFiveByCountThenName() throws Exception {
		long memberId = newMemberId();
		// 마포 3 · 성동 2 · 그 밖에 1씩 다섯 구 → 상위 5개만 남고, 1건짜리는 이름순으로 끊긴다.
		givenRecord(memberId, "act-a1", "A", "서울 마포구 1로 1", "2026-03-01T10:00");
		givenRecord(memberId, "act-a2", "B", "서울 마포구 2로 2", "2026-03-02T10:00");
		givenRecord(memberId, "act-a3", "C", "서울 마포구 3로 3", "2026-03-03T10:00");
		givenRecord(memberId, "act-a4", "D", "서울 성동구 1로 1", "2026-03-04T10:00");
		givenRecord(memberId, "act-a5", "E", "서울 성동구 2로 2", "2026-03-05T10:00");
		givenRecord(memberId, "act-a6", "F", "서울 종로구 1로 1", "2026-03-06T10:00");
		givenRecord(memberId, "act-a7", "G", "서울 중구 1로 1", "2026-03-07T10:00");
		givenRecord(memberId, "act-a8", "H", "서울 강남구 1로 1", "2026-03-08T10:00");
		givenRecord(memberId, "act-a9", "I", "서울 용산구 1로 1", "2026-03-09T10:00");

		JsonNode areas = getActivity(memberId).at("/data/areas");

		assertThat(areas).hasSize(5);
		assertThat(areas.get(0).get("district").asText()).isEqualTo("마포구");
		assertThat(areas.get(0).get("recordCount").asInt()).isEqualTo(3);
		assertThat(areas.get(1).get("district").asText()).isEqualTo("성동구");
		assertThat(areas.get(1).get("recordCount").asInt()).isEqualTo(2);
		// 1건 동점 넷 중 이름 오름차순으로 앞선 셋만 남는다: 강남구 < 용산구 < 종로구 < 중구
		assertThat(List.of(
			areas.get(2).get("district").asText(),
			areas.get(3).get("district").asText(),
			areas.get(4).get("district").asText()))
			.containsExactly("강남구", "용산구", "종로구");
	}

	@Test
	@DisplayName("counts가 맥락 메모·컬렉션·기록한 달 수를 담는다")
	void countsCoverContextsCollectionsAndRecordedMonths() throws Exception {
		long memberId = newMemberId();
		long r1 = givenRecord(memberId, "act-c1", "A", "서울 마포구 1로 1", "2026-03-01T10:00");
		long r2 = givenRecord(memberId, "act-c2", "B", "서울 마포구 2로 2", "2026-03-02T10:00");
		givenRecord(memberId, "act-c3", "C", "서울 마포구 3로 3", "2026-05-02T10:00");
		createCollection(memberId, "책장 하나", List.of(r1, r2));

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.counts.contextCount").value(3))
			.andExpect(jsonPath("$.data.counts.collectionCount").value(1))
			// 3월과 5월 — 기록이 실제로 있는 달만 센다(months 길이와 다르다)
			.andExpect(jsonPath("$.data.counts.recordedMonthCount").value(2));
	}

	@Test
	@DisplayName("highlights가 처음 기록한 곳·가장 붐빈 하루·가장 최근 기록을 담는다")
	void highlightsCoverFirstBusiestAndLast() throws Exception {
		long memberId = newMemberId();
		givenRecord(memberId, "act-h1", "성수 앤트러사이트", "서울 성동구 성수이로 87", "2026-01-14T09:00");
		givenRecord(memberId, "act-h2", "B", "서울 마포구 2로 2", "2026-05-17T09:00");
		givenRecord(memberId, "act-h3", "C", "서울 마포구 3로 3", "2026-05-17T13:00");
		givenRecord(memberId, "act-h4", "D", "서울 마포구 4로 4", "2026-05-17T20:00");
		givenRecord(memberId, "act-h5", "연남 커피리브레", "서울 마포구 성미산로 198", "2026-06-01T09:00");

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.highlights.firstPlaceName").value("성수 앤트러사이트"))
			.andExpect(jsonPath("$.data.highlights.busiestDay.date").value("2026-05-17"))
			.andExpect(jsonPath("$.data.highlights.busiestDay.recordCount").value(3))
			.andExpect(jsonPath("$.data.highlights.lastPlaceName").value("연남 커피리브레"));
	}

	/**
	 * 경계 검증. KST 2026-05-18 00:30은 UTC로 2026-05-17 15:30이다 — UTC로 끊으면 17일에 붙어
	 * 17일이 3건이 되고, KST로 끊어야 18일에 붙어 17일이 2건으로 남는다. 같은 이유로 이 기록은
	 * 5월에 속한다(월 경계는 이 자리에서 갈리지 않지만 같은 표현식을 공유한다).
	 */
	@Test
	@DisplayName("하루 경계를 KST로 끊는다 — UTC로 끊으면 전날에 붙는 시각")
	void dayBoundaryFollowsSeoulTime() throws Exception {
		long memberId = newMemberId();
		givenRecord(memberId, "act-tz1", "A", "서울 마포구 1로 1", "2026-05-17T09:00");
		givenRecord(memberId, "act-tz2", "B", "서울 마포구 2로 2", "2026-05-17T21:00");
		// KST 18일 00:30 — UTC로는 17일 15:30이다
		givenRecord(memberId, "act-tz3", "C", "서울 마포구 3로 3", "2026-05-18T00:30");
		// 18일을 이기지 못하도록 17일에 하나 더 두지 않는다. 17일 2건 vs 18일 1건이라 17일이 최다다.

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.highlights.busiestDay.date").value("2026-05-17"))
			.andExpect(jsonPath("$.data.highlights.busiestDay.recordCount").value(2));
	}

	@Test
	@DisplayName("가장 붐빈 하루가 동점이면 더 최근 날짜를 고른다")
	void busiestDayTieBreaksToMoreRecent() throws Exception {
		long memberId = newMemberId();
		givenRecord(memberId, "act-b1", "A", "서울 마포구 1로 1", "2026-03-10T09:00");
		givenRecord(memberId, "act-b2", "B", "서울 마포구 2로 2", "2026-03-10T11:00");
		givenRecord(memberId, "act-b3", "C", "서울 마포구 3로 3", "2026-04-20T09:00");
		givenRecord(memberId, "act-b4", "D", "서울 마포구 4로 4", "2026-04-20T11:00");

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.highlights.busiestDay.date").value("2026-04-20"))
			.andExpect(jsonPath("$.data.highlights.busiestDay.recordCount").value(2));
	}

	@Test
	@DisplayName("소프트 삭제된 Record와 남의 Record는 모든 집계에서 빠진다")
	void softDeletedAndOthersRecordsAreExcluded() throws Exception {
		long memberId = newMemberId();
		long live = givenRecord(memberId, "act-x1", "살아남을 곳", "서울 마포구 1로 1", "2026-03-01T10:00");
		long dead = givenRecord(memberId, "act-x2", "지워질 곳", "서울 종로구 1로 1", "2026-03-02T10:00");
		long otherId = newMemberId();
		givenRecord(otherId, "act-x3", "남의 곳", "서울 중구 1로 1", "2026-03-03T10:00");

		softDelete("core.record", dead);

		JsonNode data = getActivity(memberId).at("/data");

		assertThat(data.at("/totals/placeCount").asInt()).isEqualTo(1);
		assertThat(data.at("/totals/districtCount").asInt()).isEqualTo(1);
		assertThat(data.at("/areas").size()).isEqualTo(1);
		assertThat(data.at("/areas/0/district").asText()).isEqualTo("마포구");
		assertThat(data.at("/highlights/firstPlaceName").asText()).isEqualTo("살아남을 곳");
		// 지우지 않은 쪽이 남아 있는 것까지 확인한다 — 전부 0이라 통과하는 것을 막는다.
		assertThat(deletedAtOf("core.record", live)).isNull();
	}

	@Test
	@DisplayName("소프트 삭제된 Context·Collection은 counts에서 빠진다")
	void softDeletedContextsAndCollectionsAreExcluded() throws Exception {
		long memberId = newMemberId();
		long r1 = givenRecord(memberId, "act-s1", "A", "서울 마포구 1로 1", "2026-03-01T10:00");
		long r2 = givenRecord(memberId, "act-s2", "B", "서울 마포구 2로 2", "2026-03-02T10:00");
		long keptCollection = createCollection(memberId, "남을 책장", List.of(r1));
		long goneCollection = createCollection(memberId, "지울 책장", List.of(r2));

		softDelete("core.context", firstContextId(memberId, r2));
		softDelete("core.collection", goneCollection);

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.counts.contextCount").value(1))
			.andExpect(jsonPath("$.data.counts.collectionCount").value(1));

		assertThat(deletedAtOf("core.collection", keptCollection)).isNull();
	}

	@Test
	@DisplayName("기록이 없는 계정은 빈 배열과 0으로 채워진 200을 받는다")
	void freshMemberGetsEmptyAggregates() throws Exception {
		long memberId = newMemberId();

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totals.placeCount").value(0))
			.andExpect(jsonPath("$.data.totals.districtCount").value(0))
			.andExpect(jsonPath("$.data.totals.firstRecordedOn").doesNotExist())
			.andExpect(jsonPath("$.data.months").isEmpty())
			.andExpect(jsonPath("$.data.areas").isEmpty())
			.andExpect(jsonPath("$.data.counts.contextCount").value(0))
			.andExpect(jsonPath("$.data.counts.collectionCount").value(0))
			.andExpect(jsonPath("$.data.counts.recordedMonthCount").value(0))
			.andExpect(jsonPath("$.data.highlights.firstPlaceName").doesNotExist())
			.andExpect(jsonPath("$.data.highlights.busiestDay").doesNotExist())
			.andExpect(jsonPath("$.data.highlights.lastPlaceName").doesNotExist());
	}

	@Test
	@DisplayName("memberId를 반환하지 않는다")
	void doesNotExposeMemberId() throws Exception {
		long memberId = newMemberId();
		givenRecord(memberId, "act-id1", "장소이름", "서울 마포구 1로 1", "2026-03-01T10:00");

		String body = mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("memberId");
	}

	@Test
	@DisplayName("미인증 요청은 401이다")
	void unauthenticatedRequestIs401() throws Exception {
		mockMvc.perform(get(PATH).with(csrf()))
			.andExpect(status().isUnauthorized());
	}

	// ---------- 로컬 픽스처 ----------

	/**
	 * 장소 이름·주소와 작성 시각을 지정해 Record를 만든다.
	 *
	 * <p>{@link CoreApiFixtures#createRecord}는 주소를 "주소"로, 시각을 DB {@code now()}로 고정한다.
	 * 이 집계는 둘 다 입력이므로 만든 뒤 {@code UPDATE}로 옮긴다 — 공유 픽스처를 고치면 다른
	 * 진행 중인 브랜치의 테스트까지 건드리게 되어 여기 지역 헬퍼로 둔다.
	 *
	 * @param createdAtKst KST 기준 벽시계 시각(예: {@code "2026-05-18T00:30"})
	 */
	private long givenRecord(long memberId, String kakaoPlaceId, String placeName, String address,
		String createdAtKst) throws Exception {
		long recordId = createRecord(memberId, kakaoPlaceId, "맥락 " + kakaoPlaceId);
		jdbcTemplate.update("UPDATE core.place SET name = ?, address = ? WHERE kakao_place_id = ?",
			placeName, address, kakaoPlaceId);
		jdbcTemplate.update("UPDATE core.record SET created_at = ? WHERE id = ?",
			Timestamp.from(LocalDateTime.parse(createdAtKst).atZone(KST).toInstant()), recordId);
		return recordId;
	}

	private JsonNode getActivity(long memberId) throws Exception {
		return parse(mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}
}
