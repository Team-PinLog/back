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
 * 문자열 검색 병합(P49 §4·§5, 병합 규칙의 실측 근거는 ai 레포 I54)의 계약을 고정한다.
 *
 * <p>이 클래스는 플래그를 <b>켠</b> 컨텍스트에서 돈다. 기본값(끔)에서 현행과 동일하다는 계약은
 * {@link RecordSearchApiTests}가 기본값 컨텍스트에서 고정한다 — 같은 클래스에 두면 둘 중 하나의
 * 플래그 상태가 거짓이 된다.
 *
 * <p>단언의 축은 넷이다.
 *
 * <ol>
 *   <li><b>게이트</b> — 단어형 질의(공백 없음·5자 이하)에서만 문자열 검색이 켜진다</li>
 *   <li><b>병합</b> — 벡터 컷 통과자와 문자열 매치의 합집합을 RRF(k=60)로 재정렬하고,
 *       {@code size} 절단은 RRF 하위부터 한 번만 한다</li>
 *   <li><b>계약 불변</b> — {@code similarity}는 원래 코사인 값을 유지한다. 문자열 단독 항목은
 *       코사인이 없으므로 0.0을 싣는다(front는 이 값을 UI에 노출하지 않는다)</li>
 *   <li><b>재검증 불변</b> — 문자열 후보도 Core 재검증(소유권·삭제·활성 Context)을 그대로 지난다</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "pinlog.search.lexical.enabled=true")
class LexicalSearchApiTests extends IntegrationContainerSupport {

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
	 * I54의 회복 사례(`신한`) 형태다. 벡터 컷을 통과하지 못한 Record라도 본문에 질의 문자열이
	 * 그대로 있으면 결과에 들어와야 한다. 문자열 단독 항목의 {@code similarity}는 0.0이고
	 * {@code matchedContext}는 매치된 Context다.
	 */
	@Test
	void wordQueryAddsARecordWhoseBodyContainsTheQueryString() throws Exception {
		long me = newMemberId();
		long vectorOnly = newRecord(me, "lex-vec", "37.5000000", "127.0000000");
		long vectorContext = newContext(vectorOnly, me, "벡터로만 잡히는 기록");
		long lexicalOnly = newRecord(me, "lex-str", "37.6000000", "127.1000000");
		long lexicalContext = newContext(lexicalOnly, me, "신한은행 앞 골목의 가게");
		STUB.willReturn(new FastApiSearchStub.Match(vectorOnly, vectorContext, 0.82));

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].recordId").value(vectorOnly))
			.andExpect(jsonPath("$.data.items[0].similarity").value(0.82))
			.andExpect(jsonPath("$.data.items[1].recordId").value(lexicalOnly))
			.andExpect(jsonPath("$.data.items[1].similarity").value(0.0))
			.andExpect(jsonPath("$.data.items[1].matchedContext.contextId").value(lexicalContext));
	}

	/**
	 * <b>병합이 순서를 실제로 바꾸는 것</b>과 <b>{@code similarity}가 원값으로 남는 것</b>을 함께
	 * 고정한다. 두 신호에 모두 잡힌 Record(RRF 점수 {@code 1/62 + 1/61})가 벡터 1위
	 * ({@code 1/61})를 앞선다. 순서가 바뀌어도 값은 병합 점수가 아니라 코사인이다(P49 §4-5).
	 */
	@Test
	void recordMatchedByBothSignalsRisesAboveAVectorOnlyOne() throws Exception {
		long me = newMemberId();
		long vectorFirst = newRecord(me, "lex-both-a", "37.5000000", "127.0000000");
		long vectorFirstContext = newContext(vectorFirst, me, "벡터 1위 기록");
		long both = newRecord(me, "lex-both-b", "37.6000000", "127.1000000");
		long bothContext = newContext(both, me, "신한카드 할인 받은 가게");
		STUB.willReturn(
			new FastApiSearchStub.Match(vectorFirst, vectorFirstContext, 0.90),
			new FastApiSearchStub.Match(both, bothContext, 0.80));

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].recordId").value(both))
			.andExpect(jsonPath("$.data.items[0].similarity").value(0.80))
			.andExpect(jsonPath("$.data.items[1].recordId").value(vectorFirst))
			.andExpect(jsonPath("$.data.items[1].similarity").value(0.90));
	}

	/**
	 * 문장형 질의의 조사·부사가 만드는 우연한 일치를 막는 것이 단어형 한정의 목적이다(P49 §5).
	 * 본문에 질의가 그대로 있어도 공백이 있으면 문자열 경로 자체가 꺼져 있어야 한다.
	 */
	@Test
	void sentenceQueriesDoNotTriggerLexicalSearch() throws Exception {
		long me = newMemberId();
		long vectorOnly = newRecord(me, "lex-sent", "37.5000000", "127.0000000");
		long vectorContext = newContext(vectorOnly, me, "벡터로만 잡히는 기록");
		long lexicalOnly = newRecord(me, "lex-sent-b", "37.6000000", "127.1000000");
		newContext(lexicalOnly, me, "신한 은행 바로 앞");
		STUB.willReturn(new FastApiSearchStub.Match(vectorOnly, vectorContext, 0.82));

		search(me, "신한 은행")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(vectorOnly));
	}

	/** 단어형 경계는 5자다(ai 레포 {@code SEARCH_WORD_QUERY_MAX_CHARS}와 같은 값·같은 의미). */
	@Test
	void queriesOverTheWordLengthLimitDoNotTriggerLexicalSearch() throws Exception {
		long me = newMemberId();
		long lexicalOnly = newRecord(me, "lex-long", "37.6000000", "127.1000000");
		newContext(lexicalOnly, me, "신한은행지점 방문 기록");
		STUB.willReturn();

		search(me, "신한은행지점")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	/**
	 * 전각 공백(U+3000)·NBSP(U+00A0)로 띄운 2어절 질의가 「공백 없음」으로 통과하면 문장형
	 * 질의가 문자열 경로를 타게 된다 — ai의 단어형 판정({@code str.isspace()})과 같은 방향으로
	 * 유니코드 공백 전체를 공백으로 본다.
	 */
	@Test
	void exoticSpacesAlsoMakeAQuerySentenceForm() throws Exception {
		long me = newMemberId();
		long lexicalOnly = newRecord(me, "lex-nbsp", "37.6000000", "127.1000000");
		newContext(lexicalOnly, me, "신　한 그리고 신 한");
		STUB.willReturn();

		search(me, "신　한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
		search(me, "신 한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	/**
	 * 문자열 검색의 범위 필터는 {@code core.context.member_id}다. 남의 본문에 질의가 있어도
	 * 후보조차 되면 안 된다 — 재검증이 걸러 주기를 기대하는 것과 쿼리가 애초에 좁히는 것은
	 * 방어 층이 다르다.
	 */
	@Test
	void someoneElsesBodyMatchIsNeverAdded() throws Exception {
		long me = newMemberId();
		long stranger = newMemberId();
		long strangerRecord = newRecord(stranger, "lex-other", "37.6000000", "127.1000000");
		newContext(strangerRecord, stranger, "신한은행에서 환전한 기록");
		STUB.willReturn();

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	/** 지운 Context의 본문은 매치 대상이 아니다({@code deleted_at IS NULL}). */
	@Test
	void deletedContextsAreNeverLexicallyMatched() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "lex-delctx", "37.6000000", "127.1000000");
		newContext(recordId, me, "남아 있는 무관한 본문");
		long deleted = newContext(recordId, me, "신한은행 들렀던 기록");
		contextRepository.deleteById(deleted);
		STUB.willReturn();

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	/**
	 * 문자열 후보도 Core 재검증(AI 설계 9.5)을 그대로 지난다. Record가 지워졌는데 Context 행이
	 * 남아 있는 짧은 창에서, 문자열 매치가 재검증을 우회해 지운 기록을 되살리면 안 된다.
	 */
	@Test
	void lexicallyMatchedButDeletedRecordIsStillDropped() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "lex-delrec", "37.6000000", "127.1000000");
		newContext(recordId, me, "신한은행 옆 카페");
		recordRepository.deleteById(recordId);
		STUB.willReturn();

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	/**
	 * {@code %}·{@code _}는 LIKE 와일드카드가 아니라 <b>질의의 글자</b>다. 이스케이프가 빠지면
	 * {@code 50%}가 「50으로 시작하는 모든 본문」에 매치되어, 게이트(부분일치)가 정한 자격보다
	 * 넓은 후보가 들어온다.
	 */
	@Test
	void likeWildcardsAreLiteralCharacters() throws Exception {
		long me = newMemberId();
		long literal = newRecord(me, "lex-like-a", "37.5000000", "127.0000000");
		long literalContext = newContext(literal, me, "할인 50% 쿠폰 받은 곳");
		long decoy = newRecord(me, "lex-like-b", "37.6000000", "127.1000000");
		newContext(decoy, me, "50점 만점의 가게");
		STUB.willReturn();

		search(me, "50%")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(literal))
			.andExpect(jsonPath("$.data.items[0].matchedContext.contextId").value(literalContext));
	}

	/**
	 * 최종 절단은 RRF 순위 기준으로 {@code size}를 <b>한 번만</b> 적용한다(P49 §4-4). 벡터
	 * 2위({@code 1/62})가 문자열 1위({@code 1/61})에 밀려 잘린다 — 「벡터 결과를 먼저 채우고
	 * 남는 자리에 문자열을 넣는」 구현이면 이 테스트가 깨진다.
	 */
	@Test
	void theMergedListIsCutOnceBySizeAtTheRrfTail() throws Exception {
		long me = newMemberId();
		long vectorFirst = newRecord(me, "lex-cut-a", "37.5000000", "127.0000000");
		long vectorFirstContext = newContext(vectorFirst, me, "벡터 1위");
		long vectorSecond = newRecord(me, "lex-cut-b", "37.6000000", "127.1000000");
		long vectorSecondContext = newContext(vectorSecond, me, "벡터 2위");
		long lexicalOnly = newRecord(me, "lex-cut-c", "37.7000000", "127.2000000");
		newContext(lexicalOnly, me, "신한은행 골목 안쪽");
		STUB.willReturn(
			new FastApiSearchStub.Match(vectorFirst, vectorFirstContext, 0.90),
			new FastApiSearchStub.Match(vectorSecond, vectorSecondContext, 0.80));

		mockMvc.perform(post(SEARCH_URL).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"신한\", \"size\": 2}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].recordId").value(vectorFirst))
			.andExpect(jsonPath("$.data.items[1].recordId").value(lexicalOnly));
	}

	/**
	 * 한 Record 안에서 여러 Context가 매치되면 최신 것 하나가 대표다. Record 단위 응답(AI 설계
	 * 9.4)의 문자열 쪽 등가물이고, 교체 생성(BD-07)이 만드는 구본·신본 동시 매치에서 신본을
	 * 고른다.
	 */
	@Test
	void severalMatchingContextsOfARecordCollapseIntoTheLatestOne() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "lex-dup", "37.6000000", "127.1000000");
		newContext(recordId, me, "신한은행 처음 갔던 기록");
		long latest = newContext(recordId, me, "신한은행 다시 갔던 기록");
		STUB.willReturn();

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(recordId))
			.andExpect(jsonPath("$.data.items[0].matchedContext.contextId").value(latest));
	}

	/**
	 * I54가 관측한 무결과 회복이다 — 벡터가 0건이어도 문자열 매치가 있으면 결과가 생긴다.
	 * 빈 벡터 결과에서 병합 경로가 일찍 반환해 버리는 구현이면 이 테스트가 깨진다.
	 */
	@Test
	void lexicalMatchRecoversAnOtherwiseEmptyResult() throws Exception {
		long me = newMemberId();
		long lexicalOnly = newRecord(me, "lex-empty", "37.6000000", "127.1000000");
		long lexicalContext = newContext(lexicalOnly, me, "신한은행 건너편 식당");
		STUB.willReturn();

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(lexicalOnly))
			.andExpect(jsonPath("$.data.items[0].similarity").value(0.0))
			.andExpect(jsonPath("$.data.items[0].matchedContext.contextId").value(lexicalContext))
			.andExpect(jsonPath("$.data.bounds.swLat").value(37.6));
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
