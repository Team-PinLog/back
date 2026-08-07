package com.pinlog.pinlogback.domain.search;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
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

/** 신뢰도 게이트와 관련도 재판정이 함께 켜졌을 때의 후보 순서를 고정한다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"pinlog.search.gate.enabled=true",
	"pinlog.search.gate.similarity-threshold=0.35",
	"pinlog.search.relevance-judge.enabled=true",
})
class SearchSignalCompositionApiTests extends IntegrationContainerSupport {

	private static final String SEARCH_URL = "/v1/search/records";
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
	void confidenceGateExcludesWeakCandidateBeforeTheJudgeCall() throws Exception {
		long me = newMemberId();
		long weak = newRecord(me, "composition-weak", "37.5000000", "127.0000000");
		long weakContext = newContext(weak, me, "게이트에서 제외될 약한 후보");
		long strong = newRecord(me, "composition-strong", "37.6000000", "127.1000000");
		long strongContext = newContext(strong, me, "게이트를 통과할 강한 후보");
		STUB.willReturn(
			new FastApiSearchStub.Match(weak, weakContext, 0.20),
			new FastApiSearchStub.Match(strong, strongContext, 0.80));
		STUB.willJudge(new FastApiSearchStub.Judgment(strongContext, "RELEVANT"));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(strong));
		assertThat(STUB.lastJudgeCall().candidateContextIds()).containsExactly(strongContext);
	}

	@Test
	void failedJudgeKeepsThePostGateCandidateOrder() throws Exception {
		long me = newMemberId();
		long weak = newRecord(me, "composition-fallback-weak", "37.5000000", "127.0000000");
		long weakContext = newContext(weak, me, "게이트에서 제외될 약한 후보");
		long strong = newRecord(me, "composition-fallback-strong", "37.6000000", "127.1000000");
		long strongContext = newContext(strong, me, "게이트를 통과할 강한 후보");
		STUB.willReturn(
			new FastApiSearchStub.Match(weak, weakContext, 0.20),
			new FastApiSearchStub.Match(strong, strongContext, 0.80));
		STUB.willFailJudge();

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(strong));
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
