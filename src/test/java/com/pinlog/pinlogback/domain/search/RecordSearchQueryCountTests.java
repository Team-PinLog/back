package com.pinlog.pinlogback.domain.search;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import com.pinlog.pinlogback.support.SqlQueryCounter;

/**
 * {@code keywordStatus} 집계가 N+1을 만들지 않는다 — <b>측정으로</b> 확인한다(S15P11A705-209).
 *
 * <p>이 검증이 필요한 이유는 검색이 상위 20건을 <b>한 응답에</b> 담기 때문이다. Record마다 상태를
 * 조회하도록 짜도 응답은 멀쩡하고 개발 데이터에서는 지연도 눈에 띄지 않는다. 그래서 "일괄로 짰다"는
 * 코드 읽기가 아니라 <b>결과가 늘어도 쿼리 수가 그대로다</b>를 실행으로 붙잡아 둔다.
 *
 * <p>S15P11A705-252가 Feed에서 같은 것을 {@link SqlQueryCounter}로 측정한 선례를 따른다.
 *
 * <p>{@link RecordSearchApiTests}와 나눈 이유는 {@code @Import}가 Spring 컨텍스트 캐시 키를 바꿔
 * <b>컨텍스트를 하나 더 띄우기</b> 때문이다. 계약 단언 전부를 그 비용에 얹지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SqlQueryCounter.Config.class)
class RecordSearchQueryCountTests extends IntegrationContainerSupport {

	private static final String SEARCH_URL = "/v1/search/records";

	/** 판정 상태 집계. {@code LEFT JOIN}이라 이 조각은 이 쿼리에만 있다. */
	private static final String STATUS_QUERY = "LEFT JOIN ai.context_ai_state st";

	/** Keyword 집계. 상태 집계와 달리 {@code ai.context_keyword}를 탄다. */
	private static final String KEYWORD_QUERY = "JOIN ai.context_keyword  ck";

	private static final FastApiSearchStub STUB = new FastApiSearchStub();

	@DynamicPropertySource
	static void aiServerPointsAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
	}

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

	@Autowired
	private ContextRepository contextRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	/**
	 * 결과가 3건에서 12건으로 늘어도 상태 조회는 <b>한 번</b>이다. Record별로 돌면 상위 20건에 그대로
	 * 20회가 되고, 그것이 검색 응답 지연으로 직결된다.
	 *
	 * <p>Keyword 조회도 함께 센다. <b>필드를 더하면서 기존 조회를 늘리지 않았다</b>는 것이 이 티켓의
	 * 하위 호환 주장 중 하나이고, 여기가 그것이 측정으로 드러나는 유일한 자리다.
	 */
	@Test
	void theStatusAggregateStaysOneQueryAsResultsGrow() throws Exception {
		long me = newMemberId();
		givenSearchHitting(me, 3, "small");

		queries.reset();
		search(me);
		long statusBaseline = queries.count(STATUS_QUERY);
		long keywordBaseline = queries.count(KEYWORD_QUERY);

		assertThat(statusBaseline)
			.as("판정 상태는 결과 전체의 recordId를 모아 한 번에 조회한다. 실제 SQL: %s",
				queries.matching(STATUS_QUERY))
			.isEqualTo(1);
		assertThat(keywordBaseline)
			.as("Keyword 조회도 종전대로 한 번이다")
			.isEqualTo(1);

		givenSearchHitting(me, 12, "grown");

		queries.reset();
		search(me);

		assertThat(queries.count(STATUS_QUERY))
			.as("결과가 늘어도 상태 조회 횟수는 그대로여야 한다 — 늘면 N+1이다")
			.isEqualTo(statusBaseline);
		assertThat(queries.count(KEYWORD_QUERY))
			.as("상태 필드를 더한 것이 Keyword 조회 횟수를 바꾸면 안 된다")
			.isEqualTo(keywordBaseline);
	}

	/**
	 * FastAPI가 0건을 주면 재검증도 조립도 없다 — 상태 조회 역시 나가지 않아야 한다. 빈
	 * {@code IN ()}으로 도는 쿼리는 문법 오류이거나 전체 스캔이다.
	 */
	@Test
	void noStatusQueryIsIssuedWhenThereIsNothingToAssemble() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		queries.reset();
		mockMvc.perform(post(SEARCH_URL).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"아무것도 맞지 않는 질의\"}"))
			.andExpect(status().isOk());

		assertThat(queries.count(STATUS_QUERY))
			.as("결과가 없으면 상태를 물을 Record도 없다")
			.isZero();
	}

	private void search(long memberId) throws Exception {
		mockMvc.perform(post(SEARCH_URL).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"질의\"}"))
			.andExpect(status().isOk());
	}

	/** 서로 다른 Record를 {@code count}개 만들어 대역에 싣는다. */
	private void givenSearchHitting(long memberId, int count, String tag) {
		List<FastApiSearchStub.Match> matches = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			long recordId = newRecord(memberId, "qc-" + tag + index);
			long contextId = newContext(recordId, memberId, "맥락 " + tag + index);
			// 상태를 섞는다. 한 값만 쓰면 CASE의 분기 하나만 도는 쿼리를 재는 셈이 된다.
			putState(contextId, index % 3 == 0 ? "COMPLETED" : index % 3 == 1 ? "PROCESSING" : "FAILED");
			matches.add(new FastApiSearchStub.Match(recordId, contextId, 0.90 - index * 0.01));
		}
		STUB.willReturn(matches.toArray(new FastApiSearchStub.Match[0]));
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long newRecord(long memberId, String seed) {
		String kakaoPlaceId = seed + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
		Place place = placeRepository.save(Place.create(
			kakaoPlaceId, "장소 " + seed, "주소 " + seed, null, null, null,
			new BigDecimal("37.5000000"), new BigDecimal("127.0000000")));
		return recordRepository.save(Record.create(memberId, place.getId())).getId();
	}

	private long newContext(long recordId, long memberId, String body) {
		return contextRepository.save(Context.create(recordId, memberId, body)).getId();
	}

	private void putState(long contextId, String keywordStatus) {
		jdbcTemplate.update("""
			INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status)
			VALUES (?, 'COMPLETED', ?)
			ON CONFLICT (context_id) DO UPDATE SET keyword_status = excluded.keyword_status
			""", contextId, keywordStatus);
	}
}
