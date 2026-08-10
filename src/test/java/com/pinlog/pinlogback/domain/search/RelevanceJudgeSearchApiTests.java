package com.pinlog.pinlogback.domain.search;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
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
 * 검색 결과 LLM 관련도 재판정(4번째 신호)의 계약을 고정한다. 배포 후 사용자가 보고한 실사례
 * ("부트캠프 다녔던 헬스장" 질의에서 본문에 "부트캠프"가 있는 기록이 없는 기록보다 낮은 순위로
 * 나온 것)를 이 신호가 교정한다는 것이 목적이다.
 *
 * <p>이 클래스는 플래그를 <b>켠</b> 컨텍스트에서 돈다. 기본값(끔)에서 현행과 동일하다는 계약은
 * {@link RecordSearchApiTests}가 기본값 컨텍스트에서 고정한다.
 *
 * <p>단언의 축은 셋이다.
 *
 * <ol>
 *   <li><b>필터·재정렬</b> — {@code NOT_RELEVANT}는 제거되고 나머지는 등급 desc로 재배치된다</li>
 *   <li><b>강등</b> — 판정 호출이 실패하면 판정 이전 순서를 그대로 반환한다(검색 자체는 성공)</li>
 *   <li><b>전부 무관</b> — 모든 후보가 {@code NOT_RELEVANT}면 빈 결과를 그대로 신뢰한다</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "pinlog.search.relevance-judge.enabled=true")
class RelevanceJudgeSearchApiTests extends IntegrationContainerSupport {

	private static final String SEARCH_URL = "/v1/search/records";

	/** Spring Context보다 먼저 떠야 {@code @DynamicPropertySource}가 포트를 알 수 있다. */
	private static final FastApiSearchStub STUB = new FastApiSearchStub();

	@DynamicPropertySource
	static void aiServerPointsAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
	}

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

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	/**
	 * 사용자 보고 실사례와 같은 모양이다 — 벡터 유사도로는 뒤진 기록("부트캠프"가 본문에 그대로
	 * 있는 쪽)이 판정에서 {@code VERY_RELEVANT}를 받아 1위로 올라온다.
	 */
	@Test
	void veryRelevantJudgmentOutranksAHigherSimilarityMatch() throws Exception {
		long me = newMemberId();
		long higherSimilarity = newRecord(me, "judge-a", "37.5000000", "127.0000000");
		long higherSimilarityContext = newContext(higherSimilarity, me, "군대 전역하고 다닌 헬스장");
		long literalMatch = newRecord(me, "judge-b", "37.6000000", "127.1000000");
		long literalMatchContext = newContext(literalMatch, me, "부트캠프 다닐 때 1년 동안 다니던 헬스장");
		STUB.willReturn(
			new FastApiSearchStub.Match(higherSimilarity, higherSimilarityContext, 0.85),
			new FastApiSearchStub.Match(literalMatch, literalMatchContext, 0.70));
		STUB.willJudge(
			new FastApiSearchStub.Judgment(higherSimilarityContext, "WEAKLY_RELEVANT"),
			new FastApiSearchStub.Judgment(literalMatchContext, "VERY_RELEVANT"));

		search(me, "부트캠프 다녔던 헬스장")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].recordId").value(literalMatch))
			.andExpect(jsonPath("$.data.items[1].recordId").value(higherSimilarity));
	}

	/** {@code NOT_RELEVANT}는 결과에서 빠진다 — 근거 없는 결과를 내보내지 않는다는 취지다. */
	@Test
	void notRelevantResultsAreRemoved() throws Exception {
		long me = newMemberId();
		long relevant = newRecord(me, "judge-c", "37.5000000", "127.0000000");
		long relevantContext = newContext(relevant, me, "관련 있는 기록");
		long irrelevant = newRecord(me, "judge-d", "37.6000000", "127.1000000");
		long irrelevantContext = newContext(irrelevant, me, "무관한 기록");
		STUB.willReturn(
			new FastApiSearchStub.Match(relevant, relevantContext, 0.80),
			new FastApiSearchStub.Match(irrelevant, irrelevantContext, 0.75));
		STUB.willJudge(
			new FastApiSearchStub.Judgment(relevantContext, "RELEVANT"),
			new FastApiSearchStub.Judgment(irrelevantContext, "NOT_RELEVANT"));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(relevant));
	}

	/** 모든 후보가 {@code NOT_RELEVANT}면 빈 결과를 그대로 신뢰한다. */
	@Test
	void allNotRelevantYieldsAnEmptyResult() throws Exception {
		long me = newMemberId();
		long onlyMatch = newRecord(me, "judge-e", "37.5000000", "127.0000000");
		long onlyMatchContext = newContext(onlyMatch, me, "무관한 기록");
		STUB.willReturn(new FastApiSearchStub.Match(onlyMatch, onlyMatchContext, 0.80));
		STUB.willJudge(new FastApiSearchStub.Judgment(onlyMatchContext, "NOT_RELEVANT"));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	/**
	 * 판정 호출이 실패해도 검색 자체는 성공한다 — 판정 이전(벡터 유사도) 순서를 그대로 낸다.
	 * 이 신호는 보조 신호라 장애가 주 결과를 지우면 안 된다({@code mergeLexicalMatches}와 같은
	 * 강등 정책).
	 */
	@Test
	void failedJudgeCallFallsBackToThePreJudgeOrder() throws Exception {
		long me = newMemberId();
		long first = newRecord(me, "judge-f", "37.5000000", "127.0000000");
		long firstContext = newContext(first, me, "첫 번째 기록");
		long second = newRecord(me, "judge-g", "37.6000000", "127.1000000");
		long secondContext = newContext(second, me, "두 번째 기록");
		STUB.willReturn(
			new FastApiSearchStub.Match(first, firstContext, 0.85),
			new FastApiSearchStub.Match(second, secondContext, 0.70));
		STUB.willFailJudge();

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].recordId").value(first))
			.andExpect(jsonPath("$.data.items[1].recordId").value(second));
	}

	private ResultActions search(long memberId, String query) throws Exception {
		return mockMvc.perform(post(SEARCH_URL).with(loginAs(memberId))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"query\": \"" + query + "\"}"));
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long newRecord(long memberId, String seed, String lat, String lng) {
		String kakaoPlaceId = seed + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
		Place place = placeRepository.save(Place.create(
			kakaoPlaceId, "장소 " + seed, "주소 " + seed, null, null, null,
			new BigDecimal(lat), new BigDecimal(lng)));
		return recordRepository.save(Record.create(memberId, place.getId())).getId();
	}

	private long newContext(long recordId, long memberId, String body) {
		return contextRepository.save(Context.create(recordId, memberId, body)).getId();
	}
}
