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
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 결합 신뢰도 게이트(S15P11A705-400, BD-52,
 * {@code OFFTOPIC-CONFIDENCE-GATE-HANDOFF-DRAFT.md} §4)의 계약을 고정한다.
 *
 * <p>이 클래스는 게이트와 문자열 병합을 <b>둘 다 켠</b> 컨텍스트에서 돈다 — S2(문자열) 신호가
 * 게이트를 통과시키는지 보려면 문자열 병합도 켜져 있어야 한다. 기본값(둘 다 꺼짐)에서 게이트가
 * 아무것도 지우지 않는다는 계약은 {@link RecordSearchApiTests}가 기본값 컨텍스트에서 고정한다.
 *
 * <p>단언의 축은 넷이다.
 *
 * <ol>
 *   <li><b>S1 단독·약함</b> — 유사도가 임계값 미만이고 문자열·키워드 신호가 없으면 뺀다</li>
 *   <li><b>경계</b> — 유사도가 임계값과 같으면 남긴다({@code >=})</li>
 *   <li><b>S3(키워드)가 있으면 살아남는다</b> — 유사도가 낮아도 키워드 매치가 있으면 뺴지 않는다</li>
 *   <li><b>S2(문자열)가 있으면 살아남는다</b> — 문자열 단독 항목({@code similarity == 0.0})은
 *       그 자체로 S2가 있으므로 항상 살아남는다</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"pinlog.search.gate.enabled=true",
	"pinlog.search.gate.similarity-threshold=0.35",
	"pinlog.search.lexical.enabled=true",
})
class ConfidenceGateApiTests extends IntegrationContainerSupport {

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

	@Test
	void weakVectorOnlyResultWithNoOtherSignalIsDropped() throws Exception {
		long me = newMemberId();
		long weak = newRecord(me, "gate-weak", "37.5000000", "127.0000000");
		long weakContext = newContext(weak, me, "약한 유사도로만 걸린 기록");
		STUB.willReturn(new FastApiSearchStub.Match(weak, weakContext, 0.20));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void resultAtExactlyTheThresholdIsKept() throws Exception {
		long me = newMemberId();
		long atThreshold = newRecord(me, "gate-exact", "37.5000000", "127.0000000");
		long context = newContext(atThreshold, me, "정확히 임계값인 기록");
		STUB.willReturn(new FastApiSearchStub.Match(atThreshold, context, 0.35));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(atThreshold));
	}

	@Test
	void strongResultAboveTheThresholdIsKept() throws Exception {
		long me = newMemberId();
		long strong = newRecord(me, "gate-strong", "37.5000000", "127.0000000");
		long context = newContext(strong, me, "충분히 유사한 기록");
		STUB.willReturn(new FastApiSearchStub.Match(strong, context, 0.80));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(strong));
	}

	/** S3 — 유사도가 임계값 미만이어도 키워드 매치가 있으면 뺴지 않는다. */
	@Test
	void weakResultWithKeywordMatchIsKept() throws Exception {
		long me = newMemberId();
		long weakButMatched = newRecord(me, "gate-kw", "37.5000000", "127.0000000");
		long context = newContext(weakButMatched, me, "키워드로 살아남는 기록");
		STUB.willReturn(new FastApiSearchStub.Match(weakButMatched, context, 0.20, true));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(weakButMatched));
	}

	/**
	 * S2 — 문자열 단독 항목({@code similarity == 0.0})은 벡터 유사도 판정 대상이 아니다. 문자열
	 * 병합이 이미 자격을 정했으므로(P49 §5) 게이트가 그 판단을 다시 덮으면 안 된다.
	 */
	@Test
	void lexicalOnlyResultIsNeverDroppedByTheGate() throws Exception {
		long me = newMemberId();
		long lexicalOnly = newRecord(me, "gate-lex", "37.6000000", "127.1000000");
		long context = newContext(lexicalOnly, me, "신한은행 앞 골목의 가게");
		STUB.willReturn();

		search(me, "신한")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(lexicalOnly))
			.andExpect(jsonPath("$.data.items[0].similarity").value(0.0))
			.andExpect(jsonPath("$.data.items[0].matchedContext.contextId").value(context));
	}

	/** 섞인 응답 — 약하고 무근거인 것만 빠지고, 나머지는 근거별로 각각 남는다. bounds도 남은 것만 반영한다. */
	@Test
	void onlyTheWeakAndUnsupportedResultIsDroppedFromAMixedResponse() throws Exception {
		long me = newMemberId();
		long strong = newRecord(me, "gate-mix-strong", "37.5000000", "127.0000000");
		long strongContext = newContext(strong, me, "충분히 유사한 기록");
		long weakUnsupported = newRecord(me, "gate-mix-weak", "37.9000000", "127.9000000");
		long weakContext = newContext(weakUnsupported, me, "약하고 근거 없는 기록");
		STUB.willReturn(
			new FastApiSearchStub.Match(strong, strongContext, 0.80),
			new FastApiSearchStub.Match(weakUnsupported, weakContext, 0.10));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(strong))
			.andExpect(jsonPath("$.data.bounds.swLat").value(37.5))
			.andExpect(jsonPath("$.data.bounds.neLat").value(37.5));
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
		return contextRepository.save(com.pinlog.pinlogback.domain.record.entity.Context.create(
			recordId, memberId, body)).getId();
	}
}
