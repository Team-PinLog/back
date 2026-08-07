package com.pinlog.pinlogback.domain.record;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;
import com.pinlog.pinlogback.support.SqlQueryCounter;

/**
 * 최근 Record 목록(명세 5.9)이 항목 수와 무관하게 <b>Place·Keyword를 각각 한 번만</b> 조회한다 —
 * 측정으로 확인한다.
 *
 * <p>이 검증이 필요한 이유는 기본 페이지 크기가 1이기 때문이다. Record마다 Place를 부르도록 짜도
 * 기본 요청에서는 쿼리가 하나뿐이라 아무 증상이 없고, 프론트가 {@code size}를 올리는 순간에만
 * 드러난다. 그래서 "일괄로 짰다"를 코드 읽기가 아니라 <b>항목이 늘어도 쿼리 수가 그대로다</b>로
 * 붙잡아 둔다({@code RecordSearchQueryCountTests}와 같은 방식).
 *
 * <p>{@code @Import}가 Spring 컨텍스트 캐시 키를 바꿔 <b>컨텍스트를 하나 더 띄우므로</b> 계약 단언
 * 전부를 그 비용에 얹지 않는다 — 계약은 {@link RecordRecentApiTests}에 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SqlQueryCounter.Config.class)
class RecordRecentQueryCountTests extends IntegrationContainerSupport {

	private static final String RECENT_URL = "/v1/records/recent";

	/** Record 페이지. 커서 없는 첫 페이지 쿼리다. */
	private static final String RECORD_QUERY = "from core.record";

	/** Place 일괄 조회. */
	private static final String PLACE_QUERY = "from core.place";

	/** Keyword 집계. {@code ai.context_keyword}를 타는 것은 이 쿼리뿐이다. */
	private static final String KEYWORD_QUERY = "JOIN ai.context_keyword  ck";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SqlQueryCounter queries;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Test
	void placeAndKeywordLookupsStayOneQueryEachAsThePageGrows() throws Exception {
		long me = newMemberId();
		saveRecords(me, 3);

		queries.reset();
		fetch(me, 20);
		long placeBaseline = queries.count(PLACE_QUERY);
		long keywordBaseline = queries.count(KEYWORD_QUERY);

		assertThat(queries.count(RECORD_QUERY))
			.as("Record 페이지는 한 번에 읽는다. 실제 SQL: %s", queries.matching(RECORD_QUERY))
			.isEqualTo(1);
		assertThat(placeBaseline)
			.as("Place는 페이지의 placeId를 모아 한 번에 조회한다. 실제 SQL: %s", queries.matching(PLACE_QUERY))
			.isEqualTo(1);
		assertThat(keywordBaseline)
			.as("Keyword도 한 번이다")
			.isEqualTo(1);

		long grown = newMemberId();
		saveRecords(grown, 12);

		queries.reset();
		fetch(grown, 20);

		assertThat(queries.count(PLACE_QUERY))
			.as("항목이 3건에서 12건으로 늘어도 Place 조회 횟수는 그대로여야 한다 — 늘면 N+1이다")
			.isEqualTo(placeBaseline);
		assertThat(queries.count(KEYWORD_QUERY))
			.as("Keyword 조회도 마찬가지다")
			.isEqualTo(keywordBaseline);
	}

	/**
	 * 7일 창 안에 아무것도 없으면 조립할 것이 없다 — Place·Keyword 조회가 아예 나가지 않아야 한다.
	 * 빈 {@code IN ()}으로 도는 쿼리는 문법 오류이거나 전체 스캔이다.
	 */
	@Test
	void noAssemblyQueryIsIssuedWhenThePageIsEmpty() throws Exception {
		long me = newMemberId();

		queries.reset();
		fetch(me, 20);

		assertThat(queries.count(PLACE_QUERY)).isZero();
		assertThat(queries.count(KEYWORD_QUERY)).isZero();
	}

	private void fetch(long memberId, int size) throws Exception {
		mockMvc.perform(get(RECENT_URL).param("size", String.valueOf(size)).with(loginAs(memberId)))
			.andExpect(status().isOk());
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private void saveRecords(long memberId, int count) {
		for (int i = 0; i < count; i++) {
			Place place = placeRepository.save(Place.create(
				"recent-count-" + UUID.randomUUID().toString().substring(0, 8),
				"장소 " + i, "주소 " + i, null, null, null,
				new BigDecimal("37.5000000"), new BigDecimal("127.0000000")));
			recordRepository.save(Record.create(memberId, place.getId()));
		}
	}
}
