package com.pinlog.pinlogback.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.record.service.RecordService;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 지도 bbox 조회 두 개가 <b>generic plan으로 갈아타지 않는지</b> 본다(S15P11A705-404).
 *
 * <p><b>무엇이 문제였나.</b> pgjdbc는 같은 문장을 5회 실행한 뒤 서버 프리페어로 전환하고, 그
 * 시점에 Postgres가 generic plan을 고를 수 있다. generic plan은 bbox 선택도를 읽을 수 없어
 * 조인 순서를 뒤집는다 — 회원의 record에서 출발하는 대신 bbox 안 place를 전부 훑고 건마다
 * record를 찔러 대부분 0행을 얻는다. record 1,012만 볼륨 실측에서 마커 조회가 2.8ms에서
 * 45.4~61.6ms로, 키워드 조회가 5.4ms에서 32~34ms로 벌어졌다.
 *
 * <p><b>왜 계획 모양을 단정하지 않는가.</b> 그 뒤집힘은 bbox 안 place 수가 회원의 record 수보다
 * 훨씬 많아야 일어난다. Testcontainers DB는 그 조건을 만들 수 없어 계획을 단정하면 <b>고치기 전에도
 * 통과</b>한다 — 판별력이 없다. 억지로 데이터를 만들어 맞추면 {@code FeedChannelPlanTests}가 겪은
 * 플레이크(행 수와 통계 갱신 타이밍에 판정이 붙었다 떨어졌다 하는 것)를 되풀이한다. 그래서 여기서는
 * <b>행 수와 무관한 두 가지</b>만 본다.
 *
 * <ol>
 * <li>bbox 경로가 도는 트랜잭션의 {@code plan_cache_mode}가 실제로 고정되는가
 * <li>그 고정 상태에서 프리페어된 문장이 generic plan을 한 번도 쓰지 않는가
 *     ({@code pg_prepared_statements.generic_plans})
 * </ol>
 *
 * <p>계획 모양과 실행 시간은 대량 볼륨에서 손으로 확인하고 구현 보고서에 남긴다 — 이 스위트가
 * 덮지 못하는 부분이므로 덮은 척하지 않는다.
 *
 * <p><b>패키지가 {@code ai.repository}인 이유:</b> 이 판정은 {@code ContextKeywordRepository}의
 * bbox SQL을 대상으로 하며, 뒤집힘을 재현하는 쪽이 그 문장이다. {@code FeedChannelPlanTests}가
 * 같은 이유로 리포지터리 패키지에 있다.
 */
@SpringBootTest
class MapBboxPlanCacheTests extends IntegrationContainerSupport {

	/** 고정하지 않은 상태의 기본값. Postgres 기본값이며 이 값이면 프리페어 후 generic plan이 열린다. */
	private static final String NOT_PINNED = "auto";

	private static final String PINNED = "force_custom_plan";

	/** 서울 도심 한 조각. 실제 데이터가 없어도 계획·설정 판정에는 영향이 없다. */
	private static final BigDecimal SW_LAT = new BigDecimal("37.4");
	private static final BigDecimal SW_LNG = new BigDecimal("126.8");
	private static final BigDecimal NE_LAT = new BigDecimal("37.7");
	private static final BigDecimal NE_LNG = new BigDecimal("127.2");

	/** pgjdbc가 서버 프리페어로 갈아타는 기준(prepareThreshold 기본 5)을 넘기는 횟수. */
	private static final int PAST_PREPARE_THRESHOLD = 7;

	@Autowired
	private RecordService recordService;

	@Autowired
	private ContextKeywordRepository contextKeywordRepository;

	@Autowired
	private NamedParameterJdbcTemplate jdbc;

	/**
	 * 마커 조회의 bbox 경로가 계획을 고정한다.
	 *
	 * <p>테스트가 {@code @Transactional}이므로 서비스의 {@code @Transactional(readOnly = true)}는
	 * 이 트랜잭션에 합류한다. {@code SET LOCAL}은 트랜잭션 범위라, 서비스가 걸었으면 호출이 끝난
	 * 뒤 이 자리에서 읽힌다 — 그것이 이 단정의 근거다.
	 */
	@Test
	@Transactional
	void markerBboxQueryPinsTheCustomPlan() {
		recordService.map(insertMember(), SW_LAT, SW_LNG, NE_LAT, NE_LNG, null);

		assertThat(planCacheMode())
			.as("마커 bbox 조회가 계획을 고정하지 않는다 — 프리페어 전환 후 generic plan으로 뒤집힌다")
			.isEqualTo(PINNED);
	}

	/** 키워드 조회의 bbox 경로가 계획을 고정한다. */
	@Test
	@Transactional
	void keywordBboxQueryPinsTheCustomPlan() {
		recordService.mapKeywords(insertMember(), SW_LAT, SW_LNG, NE_LAT, NE_LNG);

		assertThat(planCacheMode())
			.as("키워드 bbox 조회가 계획을 고정하지 않는다 — 프리페어 전환 후 generic plan으로 뒤집힌다")
			.isEqualTo(PINNED);
	}

	/**
	 * bbox를 생략한 경로는 고정하지 않는다.
	 *
	 * <p><b>이 테스트가 위 두 개의 판별력을 만든다.</b> 이것이 없으면 커넥션마다 무조건 고정해 놓고도
	 * 위 두 단정이 통과하므로, "bbox 경로에만 걸었다"는 것이 확인되지 않는다.
	 *
	 * <p>bbox 없는 경로에도 계획 취약점이 있다는 관찰은 별개다({@code TOP_KEYWORDS_FOR_OWNER_SQL}의
	 * 주석). 그 범위는 S15P11A705-405의 전수 감사가 판단한다 — 여기서 함께 걸면 감사 결과를 미리
	 * 단정하는 것이 된다.
	 */
	@Test
	@Transactional
	void pathsWithoutBboxAreLeftAlone() {
		recordService.mapKeywords(insertMember(), null, null, null, null);

		assertThat(planCacheMode())
			.as("bbox 없는 경로까지 고정됐다 — 이 티켓의 범위는 bbox 조회 둘이다(S15P11A705-405 참조)")
			.isEqualTo(NOT_PINNED);
	}

	/**
	 * 고정한 상태에서 프리페어 기준을 넘겨도 generic plan을 쓰지 않는다.
	 *
	 * <p>한 트랜잭션 안에서 반복하므로 커넥션이 하나로 고정된다 — pgjdbc의 프리페어 횟수는 커넥션마다
	 * 세므로, 트랜잭션을 나누면 풀이 커넥션을 갈아 기준을 못 넘긴다.
	 *
	 * <p>{@code from_sql = false}인 항목만 본다. 프로토콜로 프리페어된 문장(pgjdbc가 만든 것)이
	 * 그쪽이고, SQL {@code PREPARE}로 만든 것과 섞이면 무엇을 세는지 흐려진다.
	 *
	 * <p><b>절대값이 아니라 증분을 본다.</b> 프리페어된 문장은 트랜잭션이 끝나도 풀 커넥션에 남고
	 * 계수도 함께 남는다. 절대값으로 단정하면 같은 커넥션을 먼저 쓴 테스트가 올려 둔 값까지 세어
	 * <b>실행 순서에 따라</b> 판정이 갈린다 — 실제로 그렇게 실패했다(먼저 돈
	 * {@link #theGenericPlanCounterActuallyMoves}가 남긴 3). 이 호출들이 <em>새로</em> 만든 generic
	 * plan이 없다는 것이 단정할 값이다.
	 */
	@Test
	@Transactional
	void pinnedTransactionNeverFallsBackToTheGenericPlan() {
		long memberId = insertMember();
		long before = genericPlanCount();

		for (int i = 0; i < PAST_PREPARE_THRESHOLD; i++) {
			recordService.mapKeywords(memberId, SW_LAT, SW_LNG, NE_LAT, NE_LNG);
		}

		assertThat(genericPlanCount() - before)
			.as("고정했는데도 generic plan으로 갈아탔다. 계획 고정이 실제 쿼리 커넥션에 닿지 않는다")
			.isZero();
	}

	/**
	 * 같은 자리에서 generic plan을 강제하면 계수가 오른다.
	 *
	 * <p><b>위 테스트의 판별력을 보이는 자리다.</b> 계수가 어떤 상황에서도 0이면 위 단정은 아무것도
	 * 확인하지 않는다. 여기서는 {@code force_generic_plan}으로 뒤집어 계수가 실제로 움직이는 것을
	 * 보인다 — 그래야 위의 0이 "고정이 먹었다"는 뜻이 된다.
	 *
	 * <p>Testcontainers의 적은 행 수와 무관하게 결정적이다. 계획 선택을 비용 추정에 맡기지 않고
	 * 강제하기 때문이다.
	 */
	@Test
	@Transactional
	void theGenericPlanCounterActuallyMoves() {
		long memberId = insertMember();
		long before = genericPlanCount();

		jdbc.getJdbcTemplate().execute("SET LOCAL plan_cache_mode = force_generic_plan");
		for (int i = 0; i < PAST_PREPARE_THRESHOLD; i++) {
			contextKeywordRepository.findTopKeywordsInBounds(memberId, SW_LAT, SW_LNG, NE_LAT, NE_LNG, 5);
		}

		assertThat(genericPlanCount() - before)
			.as("generic plan을 강제했는데 계수가 오르지 않는다 — 이 계수로는 계획 전환을 관측할 수 없다")
			.isPositive();
	}

	private String planCacheMode() {
		return jdbc.queryForObject("SELECT current_setting('plan_cache_mode')", Map.of(), String.class);
	}

	/** pgjdbc가 프리페어한 bbox 키워드 문장이 generic plan을 쓴 횟수. */
	private long genericPlanCount() {
		List<Long> counts = jdbc.queryForList("""
			SELECT generic_plans FROM pg_prepared_statements
			WHERE from_sql = false AND statement LIKE '%keyword_preset%'
			""", Map.of(), Long.class);
		return counts.stream().mapToLong(Long::longValue).sum();
	}

	private long insertMember() {
		Long id = jdbc.queryForObject(
			"INSERT INTO core.member (created_at) VALUES (now()) RETURNING id", Map.of(), Long.class);
		return id == null ? 0L : id;
	}
}
