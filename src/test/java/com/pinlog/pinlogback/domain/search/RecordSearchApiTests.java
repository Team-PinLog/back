package com.pinlog.pinlogback.domain.search;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import com.pinlog.pinlogback.global.common.InputLimits;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * {@code POST /v1/search/records}의 계약(API 명세 6.1)과 <b>Spring 최종 검증</b>(AI 설계 9.5)을
 * 함께 고정한다(S15P11A705-135).
 *
 * <p>이 클래스의 절반은 <b>대역이 거짓말을 했을 때</b>를 다룬다. FastAPI는 {@code ai} 스키마의
 * 비정규화 {@code user_id}로 범위를 좁힐 뿐 인가를 판단하지 않으므로, 그 응답을 그대로 내보내면
 * 남의 기록이 검색 결과로 새어 나갈 수 있다. 여기서 확인하려는 것은 "정상 응답을 잘 조립하는가"가
 * 아니라 <b>"틀린 응답을 걸러내는가"</b>다.
 *
 * <p>나머지 절반은 <b>실패를 빈 결과로 바꾸지 않는가</b>다. Profile 불일치(422)를 빈 배열로 치환하면
 * 설정이 어긋난 채 배포된 사실이 "검색 결과 없음"으로 보인다(ai 레포 {@code docs/spec/model-profile.md}
 * 3.1). 그래서 <b>빈 결과가 아니라 오류</b>인 것 자체가 단언 대상이다.
 *
 * <p>Core 데이터는 리포지토리로 직접 만든다. {@code POST /v1/records}를 쓰면 커밋 이후 리스너가
 * {@code process}를 부르는데, 이 클래스의 base-url은 검색만 받는 대역이라 그 호출이 404로 떨어져
 * 로그만 어지럽힌다 — 검증 대상도 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordSearchApiTests extends IntegrationContainerSupport {

	private static final String SEARCH_URL = "/v1/search/records";

	/** Spring Context보다 먼저 떠야 {@code @DynamicPropertySource}가 포트를 알 수 있다. */
	private static final FastApiSearchStub STUB = new FastApiSearchStub();

	@DynamicPropertySource
	static void aiServerPointsAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
	}

	/**
	 * 설정에서 읽은 값이 그대로 실려 나가는지 보려면 <b>덮어쓰지 않은</b> 값이어야 한다. 테스트가
	 * 자기 값을 주입하면 "무엇이든 실어 보낸다"만 증명되고, 배포에 실린 값이 맞는지는 증명되지 않는다.
	 */
	@Value("${pinlog.ai.embedding-profile}")
	private String configuredEmbeddingProfile;

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

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	@Test
	void assemblesRecordUnitItemsWithTheMatchedContextAndEnclosingBounds() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-ok", "37.5447000", "127.0557000");
		long contextId = newContext(recordId, me, "비 오는 날 친구와 가려고 저장");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, contextId, 0.82));

		search(me, "비 오는 날 카페")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(recordId))
			.andExpect(jsonPath("$.data.items[0].similarity").value(0.82))
			.andExpect(jsonPath("$.data.items[0].place.name").value("장소 search-ok"))
			.andExpect(jsonPath("$.data.items[0].place.lat").value(37.5447))
			.andExpect(jsonPath("$.data.items[0].matchedContext.contextId").value(contextId))
			.andExpect(jsonPath("$.data.items[0].matchedContext.body").value("비 오는 날 친구와 가려고 저장"))
			.andExpect(jsonPath("$.data.items[0].matchedContext.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.data.items[0].keywords").isArray())
			.andExpect(jsonPath("$.data.items[0].createdAt").isNotEmpty())
			.andExpect(jsonPath("$.data.bounds.swLat").value(37.5447))
			.andExpect(jsonPath("$.data.bounds.neLng").value(127.0557));
	}

	/**
	 * 이 단언이 곧 BD-39다. Spring이 자기 설정에서 읽은 Profile을 실어 보내야 FastAPI의 런타임 대조가
	 * 성립한다(공용 계약 05 §7.1). 보내지 않거나 빈 값을 보내면 대조 장치가 통째로 무력해진다.
	 */
	@Test
	void sendsTheConfiguredEmbeddingProfileAndTheAuthenticatedMemberIdOnEveryRequest() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		search(me, "아무 질의").andExpect(status().isOk());

		FastApiSearchStub.Received call = STUB.lastCall();
		assertThat(call).as("검색은 동기 호출이므로 응답 시점에 이미 도착해 있어야 한다").isNotNull();
		assertThat(call.embeddingProfile())
			.as("설정에서 읽은 Profile이 그대로 실려야 FastAPI가 대조할 수 있다")
			.isEqualTo(configuredEmbeddingProfile)
			.isNotBlank();
		assertThat(call.userId())
			.as("검색 범위는 인증에서 해석한 memberId다 — 요청 본문이 정하지 않는다")
			.isEqualTo(me);
		assertThat(call.internalSecret()).isEqualTo("test-internal-secret");
		assertThat(call.query()).isEqualTo("아무 질의");
	}

	@Test
	void forwardsTheRequestedSizeAsTheFastApiLimit() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		mockMvc.perform(post(SEARCH_URL).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"질의\", \"size\": 7}"))
			.andExpect(status().isOk());

		assertThat(STUB.lastCall().limit()).isEqualTo(7);
	}

	@Test
	void defaultsToTwentyWhenSizeIsOmitted() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		search(me, "질의").andExpect(status().isOk());

		assertThat(STUB.lastCall().limit())
			.as("API 명세 6.1의 기본값")
			.isEqualTo(20);
	}

	/**
	 * <b>이 PR의 핵심 단언이다.</b> 422를 빈 결과로 바꾸면 "설정이 어긋났다"가 "일치하는 기록이 없다"로
	 * 보인다. 200과 빈 배열이 아니라 오류여야 한다.
	 */
	@Test
	void profileMismatchIsReportedAsAnErrorAndNeverAsAnEmptyResult() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.PROFILE_MISMATCH);

		search(me, "질의")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("SEARCH_PROFILE_MISMATCH"))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	/**
	 * FastAPI는 요청 검증 실패에도 422를 쓴다. 상태 코드만 보고 Profile 불일치로 단정하면 운영자가
	 * 있지도 않은 설정 불일치를 쫓는다 — 본문에 양쪽 Profile이 실려 있는지로 가른다.
	 */
	@Test
	void anotherKindOf422IsNotMisreportedAsAProfileMismatch() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.VALIDATION_ERROR);

		search(me, "질의")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("SEARCH_UNAVAILABLE"));
	}

	/** 본문이 JSON이 아닌 422(프록시·게이트웨이가 끼어든 경우). 파싱 실패가 500이 되면 안 된다. */
	@Test
	void anUnparseable422IsTreatedAsAPlainFailure() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.UNPARSEABLE_422);

		search(me, "질의")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("SEARCH_UNAVAILABLE"));
	}

	/**
	 * 시크릿·헤더 설정 문제는 실제로 가장 자주 나는 운영 오류다. 사용자에게는 다른 장애와 똑같이
	 * 보이지만 로그에서는 갈라져야 하므로, 여기서는 <b>빈 결과가 아니라는 것</b>만 고정한다.
	 */
	@Test
	void rejectedSecretBecomesAnErrorResponse() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.UNAUTHORIZED);

		search(me, "질의")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("SEARCH_UNAVAILABLE"));
	}

	/**
	 * 계약을 어긴 200 응답 둘. <b>상대 응답의 결함이 우리 500으로 나타나면 안 된다</b> — 사용자에게는
	 * "서버가 터졌다"로 보이고 원인은 상대에게 있어 우리 로그만 봐서는 알 수 없다. 결과가 없는 것과
	 * 같게 다룬다.
	 */
	@Test
	void responseWithoutResultsFieldIsTreatedAsNoMatch() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.MISSING_RESULTS_FIELD);

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.bounds").value(Matchers.nullValue()));
	}

	@Test
	void matchesWithMissingFieldsAreDroppedInsteadOfCrashing() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.MALFORMED_RESULTS);

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	/**
	 * 필수 필드가 빈 것이 아니라 <b>배열 원소 자체가 {@code null}</b>인 경우. 최상위
	 * {@code results}는 못 믿는데 원소는 믿으면 방어 층이 어긋나고, 그 틈이 곧 {@code match.recordId()}
	 * 의 {@code NullPointerException}, 즉 <b>상대 응답의 결함이 우리 500으로</b> 나타나는 자리다.
	 */
	@Test
	void nullElementInResultsIsDroppedInsteadOfCrashing() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.NULL_MATCH_ELEMENT);

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.bounds").value(Matchers.nullValue()));
	}

	@Test
	void serverErrorFromFastApiBecomesAnErrorResponse() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.SERVER_ERROR);

		search(me, "질의")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("SEARCH_UNAVAILABLE"));
	}

	@Test
	void droppedConnectionBecomesAnErrorResponse() throws Exception {
		long me = newMemberId();
		STUB.willRespondWith(FastApiSearchStub.Mode.HANG_UP);

		search(me, "질의")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error.code").value("SEARCH_UNAVAILABLE"));
	}

	/**
	 * <b>대역이 남의 Context를 돌려줘도 응답에 없어야 한다.</b> {@code ai.context_embedding.user_id}는
	 * 비정규화 값이라 검색 범위 필터로는 충분해도 인가 근거로는 부족하다(AI 설계 9.5). 인가의 원본은
	 * Core이며, 그 재검증이 실제로 도는지는 대역이 거짓말을 해야만 드러난다.
	 */
	@Test
	void contextOwnedBySomeoneElseIsNeverReturnedEvenWhenFastApiReturnsIt() throws Exception {
		long me = newMemberId();
		long stranger = newMemberId();
		long myRecordId = newRecord(me, "search-mine", "37.5000000", "127.0000000");
		long myContextId = newContext(myRecordId, me, "내 기록");
		long strangerRecordId = newRecord(stranger, "search-theirs", "37.6000000", "127.1000000");
		long strangerContextId = newContext(strangerRecordId, stranger, "남의 기록");
		STUB.willReturn(
			new FastApiSearchStub.Match(strangerRecordId, strangerContextId, 0.99),
			new FastApiSearchStub.Match(myRecordId, myContextId, 0.50));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].recordId").value(myRecordId))
			.andExpect(jsonPath("$.data.items[*].matchedContext.body",
				Matchers.not(Matchers.hasItem("남의 기록"))))
			.andExpect(jsonPath("$.data.bounds.neLat")
				.value(37.5));
	}

	/**
	 * 삭제와 검색 사이의 짧은 창을 흉내낸다. {@code ai} 쪽 {@code is_deleted}는 보조 방어선이므로
	 * 그것이 아직 반영되지 않았다고 가정해도 Core 재검증이 막아야 한다.
	 */
	@Test
	void contextDeletedInCoreIsNotReturnedEvenWhenFastApiStillIndexesIt() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-delctx", "37.5000000", "127.0000000");
		long keptContextId = newContext(recordId, me, "남아 있는 맥락");
		long deletedContextId = newContext(recordId, me, "지워진 맥락");
		contextRepository.deleteById(deletedContextId);
		STUB.willReturn(new FastApiSearchStub.Match(recordId, deletedContextId, 0.90));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());

		assertThat(contextRepository.findById(keptContextId))
			.as("Record 자체는 살아 있다 — 걸러진 근거가 Record 삭제가 아니라 Context 삭제임을 못박는다")
			.isPresent();
	}

	@Test
	void deletedRecordIsNotReturnedEvenWhenFastApiStillIndexesIt() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-delrec", "37.5000000", "127.0000000");
		long contextId = newContext(recordId, me, "곧 지울 기록");
		recordRepository.deleteById(recordId);
		STUB.willReturn(new FastApiSearchStub.Match(recordId, contextId, 0.90));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void anUnknownRecordIdFromFastApiIsSilentlyDropped() throws Exception {
		long me = newMemberId();
		STUB.willReturn(new FastApiSearchStub.Match(999_999_999L, 999_999_999L, 0.95));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.bounds").value(Matchers.nullValue()));
	}

	/**
	 * 유사도는 Context 단위로 계산되지만 응답은 Record 단위다(AI 설계 9.4). FastAPI가 이미 Record 단위로
	 * 집계하지만 Spring도 순서를 보존한 distinct로 이중 보장한다(응답 조립 명세 6.3).
	 */
	@Test
	void severalContextsOfOneRecordCollapseIntoASingleItemKeepingTheBestMatch() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-dup", "37.5000000", "127.0000000");
		long best = newContext(recordId, me, "가장 잘 맞는 맥락");
		long worse = newContext(recordId, me, "덜 맞는 맥락");
		STUB.willReturn(
			new FastApiSearchStub.Match(recordId, best, 0.91),
			new FastApiSearchStub.Match(recordId, worse, 0.40));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].similarity").value(0.91))
			.andExpect(jsonPath("$.data.items[0].matchedContext.contextId").value(best));
	}

	@Test
	void keepsTheSimilarityOrderGivenByFastApi() throws Exception {
		long me = newMemberId();
		long low = newRecord(me, "search-ord-lo", "37.4000000", "126.9000000");
		long high = newRecord(me, "search-ord-hi", "37.6000000", "127.1000000");
		long lowContext = newContext(low, me, "낮은 유사도");
		long highContext = newContext(high, me, "높은 유사도");
		STUB.willReturn(
			new FastApiSearchStub.Match(high, highContext, 0.88),
			new FastApiSearchStub.Match(low, lowContext, 0.31));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].recordId").value(high))
			.andExpect(jsonPath("$.data.items[1].recordId").value(low))
			.andExpect(jsonPath("$.data.bounds.swLat").value(37.4))
			.andExpect(jsonPath("$.data.bounds.neLat").value(37.6));
	}

	@Test
	void noMatchIsAnEmptyItemListWithNullBounds() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		search(me, "아무것도 맞지 않는 질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.bounds").value(Matchers.nullValue()));
	}

	/**
	 * {@code keywords}는 매칭 Context의 것이 아니라 <b>Record의 활성 Context 전체 집계</b>다
	 * (API 명세 6.1). 매칭 Context만 보면 같은 Record의 다른 Context가 가진 Keyword가 사라진다.
	 */
	@Test
	void keywordsAreAggregatedOverEveryActiveContextOfTheRecord() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-kw", "37.5000000", "127.0000000");
		long matched = newContext(recordId, me, "매칭된 맥락");
		long other = newContext(recordId, me, "매칭되지 않은 맥락");
		attachKeyword(matched, insertPreset("친구", "PUBLIC", true));
		attachKeyword(other, insertPreset("비 오는 날", "PUBLIC", true));
		attachKeyword(other, insertPreset("감춘 것", "BLOCKED", true));
		STUB.willReturn(new FastApiSearchStub.Match(recordId, matched, 0.77));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywords",
				Matchers.containsInAnyOrder("친구", "비 오는 날")));
	}

	/**
	 * {@code PRIVATE_ONLY}는 05 §8.4에서 <b>본인 조회와 타인 조회를 가르는 바로 그 값</b>이다.
	 * 이 단언이 없으면 화이트리스트를 {@code IN ('PUBLIC')}으로 좁혀도 테스트가 전부 초록이고,
	 * 실제로는 소유자 Keyword의 절반이 조용히 사라진다 — 가시성 필터를 WHERE 절에 둔 이유가
	 * 그 조용한 누락을 막는 것인데, 잡을 단언이 없으면 이유가 지켜지는지 알 수 없다.
	 */
	@Test
	void privateOnlyKeywordsAreVisibleToTheOwner() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-kw-private", "37.5000000", "127.0000000");
		long matched = newContext(recordId, me, "매칭된 맥락");
		attachKeyword(matched, insertPreset("혼자 가기 좋은", "PRIVATE_ONLY", true));
		attachKeyword(matched, insertPreset("모두에게 보이는", "PUBLIC", true));
		attachKeyword(matched, insertPreset("차단된 것", "BLOCKED", true));
		STUB.willReturn(new FastApiSearchStub.Match(recordId, matched, 0.71));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywords",
				Matchers.containsInAnyOrder("혼자 가기 좋은", "모두에게 보이는")));
	}

	/**
	 * 폐기된 Preset은 행 삭제가 아니라 {@code is_active = false}로 처리된다. 조회 조건이 그 플래그를
	 * 보지 않으면 이미 내린 Keyword가 계속 응답에 실린다.
	 */
	@Test
	void keywordsOfARetiredPresetAreExcluded() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-kw-retired", "37.5000000", "127.0000000");
		long matched = newContext(recordId, me, "매칭된 맥락");
		attachKeyword(matched, insertPreset("살아 있는 프리셋", "PUBLIC", true));
		attachKeyword(matched, insertPreset("폐기된 프리셋", "PUBLIC", false));
		STUB.willReturn(new FastApiSearchStub.Match(recordId, matched, 0.66));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywords", Matchers.contains("살아 있는 프리셋")));
	}

	@Test
	void blankQueryIsRejectedBeforeFastApiIsCalled() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		mockMvc.perform(post(SEARCH_URL).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"   \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

		assertThat(STUB.lastCall())
			.as("검증에서 거절된 요청으로 임베딩 비용을 쓰지 않는다")
			.isNull();
	}

	/**
	 * {@code SEARCH_QUERY_MAX}는 이 티켓이 <b>명세를 넘어 새로 정한 유일한 상한</b>이다(API 명세
	 * 1.9의 상한 표에 질의 길이가 없다). 상한이 없으면 임의 길이의 문자열이 그대로 외부 임베딩 호출
	 * 비용이 되므로, 그 방어가 실제로 서는지는 여기서만 드러난다 — 이 단언이 없으면 누가
	 * {@code @Size}를 떼도 나머지 테스트가 전부 통과한다.
	 */
	@Test
	void anOverlongQueryIsRejectedBeforeFastApiIsCalled() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		mockMvc.perform(post(SEARCH_URL).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"" + "가".repeat(InputLimits.SEARCH_QUERY_MAX + 1) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

		assertThat(STUB.lastCall())
			.as("거절된 질의로 임베딩 비용을 쓰지 않는다 — 상한을 둔 이유가 바로 그 비용이다")
			.isNull();
	}

	/** 상한값 자체는 통과해야 한다. 이 짝이 없으면 off-by-one({@code max = 499})이 드러나지 않는다. */
	@Test
	void queryAtTheLengthLimitIsAccepted() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		mockMvc.perform(post(SEARCH_URL).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"" + "가".repeat(InputLimits.SEARCH_QUERY_MAX) + "\"}"))
			.andExpect(status().isOk());

		assertThat(STUB.lastCall().query()).hasSize(InputLimits.SEARCH_QUERY_MAX);
	}

	@Test
	void anOversizedSizeIsRejected() throws Exception {
		long me = newMemberId();
		STUB.willReturn();

		mockMvc.perform(post(SEARCH_URL).with(loginAs(me))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"질의\", \"size\": 101}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	/**
	 * 검색은 본인 데이터 전용이므로 인증이 전제다(AI 설계 9.1).
	 *
	 * <p>CSRF 토큰은 넣고 인증만 뺀다. 빼면 상태 변경 요청이라 CSRF 필터가 먼저 403을 돌려주고
	 * (API 명세 1.7), 그러면 이 테스트가 확인하려던 <b>인증</b> 경계는 밟아 보지도 못한다.
	 */
	@Test
	void searchRequiresAuthentication() throws Exception {
		mockMvc.perform(post(SEARCH_URL).with(SecurityMockMvcRequestPostProcessors.csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"query\": \"질의\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	/**
	 * 판정이 끝났으면 {@code COMPLETED}다 — <b>Keyword가 0건이어도</b> 그렇다(응답 조립 명세 5.1).
	 * back#136의 증상이 정확히 이 조합이었다: 붙일 프리셋이 없어 0건으로 완료된 기록에 화면이
	 * "AI가 분석 중"을 영구히 띄웠다. 이 단언이 필드를 넣은 이유 자체다.
	 */
	@Test
	void aFinishedJudgementIsCompletedEvenWhenItMatchedNoKeyword() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-st-done", "37.5000000", "127.0000000");
		long contextId = newContext(recordId, me, "여기 느끼해서 다신 안감");
		putState(contextId, "COMPLETED");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, contextId, 0.81));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywords").isEmpty())
			.andExpect(jsonPath("$.data.items[0].keywordStatus").value("COMPLETED"));
	}

	/**
	 * {@code PENDING}과 {@code PROCESSING}은 응답에서 <b>같은 값으로 접힌다</b>(명세 5.1). 사용자가
	 * 내릴 판단은 「기다리면 오는가」 하나이고 두 상태는 그 답이 같다. 내부 값을 그대로 내보내면
	 * 클라이언트가 구분할 필요 없는 것을 구분하게 된다.
	 */
	@Test
	void bothPendingAndProcessingSurfaceAsProcessing() throws Exception {
		long me = newMemberId();
		for (String internal : List.of("PENDING", "PROCESSING")) {
			long recordId = newRecord(me, "search-st-" + internal, "37.5000000", "127.0000000");
			long contextId = newContext(recordId, me, "방금 저장한 맥락");
			putState(contextId, internal);
			STUB.willReturn(new FastApiSearchStub.Match(recordId, contextId, 0.79));

			search(me, "질의")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items[0].keywordStatus")
					.value("PROCESSING"));
		}
	}

	/** {@code FAILED}는 그대로 나간다 — 기다려도 오지 않으므로 재시도·문의를 유도할 수 있어야 한다. */
	@Test
	void aFailedJudgementIsReportedSoTheUserCanRetry() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-st-failed", "37.5000000", "127.0000000");
		long contextId = newContext(recordId, me, "판정이 실패한 맥락");
		putState(contextId, "FAILED");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, contextId, 0.75));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywordStatus").value("FAILED"));
	}

	/**
	 * <b>{@code PROCESSING}이 {@code FAILED}를 이긴다</b>(명세 5.1). 한 Record에 실패한 Context와
	 * 처리 중인 Context가 함께 있으면, 그 처리 중인 것이 끝나며 Keyword가 <b>더 붙는다</b> — 그
	 * 상황에서 사실인 답은 "기다리면 온다"다. 반대로 접으면 아직 올 것이 있는데 재시도를 권하게 된다.
	 *
	 * <p>이 단언이 없으면 {@code CASE}의 두 분기를 맞바꿔도 나머지 테스트가 전부 통과한다.
	 */
	@Test
	void aRecordStillProcessingOneContextIsProcessingEvenIfAnotherFailed() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-st-mixed", "37.5000000", "127.0000000");
		long failed = newContext(recordId, me, "실패한 맥락");
		long processing = newContext(recordId, me, "아직 처리 중인 맥락");
		putState(failed, "FAILED");
		putState(processing, "PROCESSING");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, failed, 0.73));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywordStatus").value("PROCESSING"));
	}

	/**
	 * {@code CANCELLED}는 집계에 넣지 않는다(명세 5.1). 그 Context는 삭제·교체되어 애초에 응답
	 * 대상이 아니므로, 그것 때문에 <b>살아 있는 Context의 상태가 바뀌면 안 된다.</b>
	 *
	 * <p>여기서 {@code deleted_at}을 채우지 않는 것이 의도다. 지우면 {@code deleted_at} 조건에
	 * 걸려 빠지므로 <b>상태값 처리 자체를 확인할 수 없다</b> — 두 방어선 중 하나만 남겨 그 하나를 본다.
	 */
	@Test
	void aCancelledContextDoesNotDragTheRecordStatus() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-st-cancelled", "37.5000000", "127.0000000");
		long live = newContext(recordId, me, "살아 있는 맥락");
		long cancelled = newContext(recordId, me, "취소된 구 맥락");
		attachKeyword(live, insertPreset("친구와", "PUBLIC", true));
		putState(cancelled, "CANCELLED");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, live, 0.72));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywordStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.data.items[0].keywords", Matchers.contains("친구와")));
	}

	/**
	 * {@code context_ai_state} 행이 없으면 {@code COMPLETED}다(명세 5.1). {@code PROCESSING}으로
	 * 접는 쪽이 직관적으로 보이지만, 그러면 <b>영영 오지 않는 것에 "분석 중"을 띄우게 되어 이
	 * 필드가 없애려는 증상이 그대로 재발한다.</b>
	 *
	 * <p>정상 경로에서는 나오지 않는 상태다 — {@code ContextAiStateRepository}가 Context 생성과 같은
	 * 트랜잭션에서 {@code PENDING}을 넣는다. 그래서 이것은 "있을 수 있는 상태"가 아니라
	 * <b>어긋난 데이터가 화면을 망가뜨리지 않는지</b>를 보는 단언이다.
	 */
	@Test
	void aRecordWithNoAiStateRowIsCompletedRatherThanForeverProcessing() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-st-norow", "37.5000000", "127.0000000");
		long contextId = newContext(recordId, me, "상태 행이 없는 맥락");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, contextId, 0.70));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywordStatus").value("COMPLETED"));
	}

	/**
	 * <b>이 티켓의 주 리스크가 하위 호환이다.</b> 필드를 더하는 변경이 기존 필드를 건드리지 않았음을
	 * 확인한다 — {@code keywordStatus}를 읽지 않는 클라이언트는 필드가 생기기 전과 완전히 같은
	 * 응답을 봐야 한다.
	 *
	 * <p>구조적 근거는 {@code ContextKeywordRepository}의 기존 세 쿼리를 <b>한 글자도 바꾸지
	 * 않았다</b>는 것이지만, "안 바꿨다"는 코드 읽기라 <b>미완료 상태에서도 배열이 그대로인지</b>를
	 * 실행으로 붙잡아 둔다. 상태 조회가 기존 조회에 조건을 흘려보내면 여기서 드러난다.
	 */
	@Test
	void addingTheStatusFieldChangesNothingAboutTheKeywordsArray() throws Exception {
		long me = newMemberId();
		long recordId = newRecord(me, "search-st-compat", "37.5000000", "127.0000000");
		long done = newContext(recordId, me, "판정이 끝난 맥락");
		long pending = newContext(recordId, me, "아직 처리 중인 맥락");
		attachKeyword(done, insertPreset("혼자 가기 좋은", "PRIVATE_ONLY", true));
		attachKeyword(done, insertPreset("모두에게 보이는", "PUBLIC", true));
		attachKeyword(done, insertPreset("차단된 것", "BLOCKED", true));
		putState(pending, "PENDING");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, done, 0.69));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywords",
				Matchers.containsInAnyOrder("혼자 가기 좋은", "모두에게 보이는")))
			.andExpect(jsonPath("$.data.items[0].keywordStatus").value("PROCESSING"))
			.andExpect(jsonPath("$.data.items[0].recordId").value(recordId))
			.andExpect(jsonPath("$.data.items[0].similarity").value(0.69))
			.andExpect(jsonPath("$.data.items[0].matchedContext.contextId").value(done))
			.andExpect(jsonPath("$.data.items[0].place.name").value("장소 search-st-compat"))
			.andExpect(jsonPath("$.data.items[0].createdAt").isNotEmpty());
	}

	/**
	 * 남의 Record 상태가 섞이지 않는다. 쿼리의 {@code ct.member_id} 조건이 지켜지는지 보는 단언이며,
	 * 빠지면 <b>남의 Context 상태가 내 검색 결과의 상태를 정한다.</b>
	 *
	 * <p>같은 Record에 두 사람의 Context가 달리는 것은 정상 데이터가 아니다 — 그래서 이것도
	 * "어긋난 데이터에서도 방어선이 서는지"를 보는 단언이다.
	 */
	@Test
	void anotherMembersContextDoesNotDecideMyRecordStatus() throws Exception {
		long me = newMemberId();
		long other = newMemberId();
		long recordId = newRecord(me, "search-st-owner", "37.5000000", "127.0000000");
		long mine = newContext(recordId, me, "내 맥락");
		long theirs = newContext(recordId, other, "남의 맥락");
		putState(mine, "COMPLETED");
		putState(theirs, "PROCESSING");
		STUB.willReturn(new FastApiSearchStub.Match(recordId, mine, 0.68));

		search(me, "질의")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].keywordStatus").value("COMPLETED"));
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

	/**
	 * Keyword 없이 판정 상태만 놓는다. {@link #attachKeyword}는 {@code COMPLETED}로 고정하므로
	 * 미완료·실패 상태를 만들 수 없다.
	 */
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
		String code = "SEARCH_" + id;
		jdbcTemplate.update("""
			INSERT INTO ai.keyword_preset
			\t(id, code, display_name, category, description, examples, embedding,
			\t embedding_profile, visibility, is_active, version)
			VALUES (?, ?, ?, 'MOOD', '테스트 프리셋', ARRAY['예시'],
			\tarray_fill(0::real, ARRAY[1536])::vector, 'test-profile', ?, ?, 1)
			""", id, code, displayName, visibility, active);
		return id;
	}
}
