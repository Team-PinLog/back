package com.pinlog.pinlogback.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pinlog.pinlogback.domain.ai.scheduler.AiRescanScheduler;
import com.pinlog.pinlogback.domain.ai.service.AiRescanProperties;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.record.dto.PlacePayload;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateRequest;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateResponse;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.service.RecordService;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 유실·정지된 AI 처리를 복구하는 회차를 검증한다(S15P11A705-159, AI 파트 소유 명세
 * {@code docs/ai/spec/ai-rescan-scheduler.md} 3~6장).
 *
 * <p><b>주기를 기다리지 않고 {@link AiRescanScheduler#runOnce()}를 직접 부른다.</b> 스케줄 실행을
 * 기다리면 테스트가 시계에 의존해 느려지고 불안정해진다. {@code @Scheduled} 등록 자체(fixedDelay인지,
 * 전용 스케줄러를 쓰는지)는 별도 테스트가 본다 — 이 클래스가 보는 것은 <b>회차가 무엇을 하는가</b>다.
 *
 * <p>만료를 만드는 방법은 {@code updated_at}을 과거로 밀어 넣는 것이다. 5분을 기다릴 수는 없다.
 * 만료 임계값을 기본값(5분·10분)이 아닌 값으로 덮어 두는 이유는 <b>그 값이 설정에서 오는지</b>를
 * 함께 확인하기 위해서다 — 코드에 상수로 박혀 있으면 아래 임계 테스트가 깨진다.
 *
 * <p>{@code @Transactional} 롤백 테스트가 아니다. {@code FOR UPDATE SKIP LOCKED}와 커밋 경계가
 * 검증 대상이라 실제로 커밋해야 한다({@code ContextAiEnqueueTests}와 같은 사정).
 */
@SpringBootTest
class AiRescanSchedulerTests extends IntegrationContainerSupport {

	/** Spring Context보다 먼저 떠야 {@code @DynamicPropertySource}가 포트를 알 수 있다. */
	private static final FastApiProcessStub STUB = new FastApiProcessStub(POSTGRES);

	private static final Duration PENDING_EXPIRY = Duration.ofMinutes(2);
	private static final Duration PROCESSING_EXPIRY = Duration.ofMinutes(4);
	/** 임계값 판정과 무관하게 "확실히 만료됐다"를 만들 때 쓰는 나이. */
	private static final Duration LONG_AGO = Duration.ofMinutes(30);

	/**
	 * Core에 존재하지 않는 {@code context_id}. {@code ai.context_ai_state}에는 {@code core.context}로
	 * 향하는 FK가 없어(V100) 이런 행을 만들 수 있고, 음수를 쓰면 IDENTITY가 만드는 실제 id와 절대
	 * 겹치지 않는다. 상태 전이만 보는 테스트는 Record·Place 조립이 필요 없다.
	 */
	private static final AtomicLong SYNTHETIC_IDS = new AtomicLong(-1_000L);

	private static final String SEED_SQL = """
		INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status, retry_count, updated_at)
		VALUES (?, ?, ?, ?, now() - make_interval(secs => ?))
		ON CONFLICT (context_id) DO UPDATE
		SET embedding_status = EXCLUDED.embedding_status,
			keyword_status = EXCLUDED.keyword_status,
			retry_count = EXCLUDED.retry_count,
			updated_at = EXCLUDED.updated_at
		""";

	@DynamicPropertySource
	static void aiServerPointsAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
		registry.add("pinlog.ai.rescan.pending-expiry", PENDING_EXPIRY::toString);
		registry.add("pinlog.ai.rescan.processing-expiry", PROCESSING_EXPIRY::toString);
	}

	@Autowired
	private AiRescanScheduler scheduler;

	@Autowired
	private AiRescanProperties properties;

	@Autowired
	private RecordService recordService;

	@Autowired
	private ContextRepository contextRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * <b>모든 상태 행의 나이를 0으로 돌린 뒤 시작한다.</b> 컨테이너는 JVM이 공유하고 이 클래스의 각
	 * 테스트는 실제로 커밋하므로, 앞선 테스트가 남긴 만료 행이 다음 회차에 후보로 섞인다. 그러면
	 * "호출이 오지 않아야 한다" 같은 단언이 남의 행 때문에 깨진다. 뒤에도 같은 정리를 해서 다른
	 * 테스트 클래스의 배경 회차에 만료 행을 물려주지 않는다.
	 */
	@BeforeEach
	void freshenEveryStateRowAndResetTheStub() {
		unstaleEveryStateRow();
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);
	}

	@AfterEach
	void freshenEveryStateRowAgain() {
		unstaleEveryStateRow();
	}

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	/**
	 * 재스캔의 본래 목적. 요청이 유실돼 {@code PENDING}으로 남은 Context를 다시 FastAPI에 보낸다.
	 *
	 * <p>본문을 함께 단언하는 이유: 명세 5.1은 이전 요청 본문을 보관해 재전송하지 말고 <b>Core를 다시
	 * 조회해</b> 만들라고 정한다. 대역이 받은 {@code text}가 Core 본문과 같다는 것이 그 확인이다.
	 */
	@Test
	void staleRowsAreSentToFastApiAgainAndTheRetryBudgetIsSpent() throws Exception {
		long contextId = newContextIdFor("rescan-retry", "재스캔이 다시 집어야 한다");
		seedState(contextId, "PENDING", "PENDING", 0, LONG_AGO);

		scheduler.runOnce();

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).as("만료된 PENDING은 다시 요청돼야 한다").isNotNull();
		assertThat(call.contextId()).isEqualTo(contextId);
		assertThat(call.text())
			.as("요청 본문은 보관해 둔 것이 아니라 Core에서 다시 읽은 것이다")
			.isEqualTo("재스캔이 다시 집어야 한다");
		assertThat(stateOf(contextId)).containsEntry("retry_count", 1);
	}

	/**
	 * {@code retry_count} 증가는 <b>외부 호출과 다른 트랜잭션</b>이다(명세 3.1의 커밋 경계). 호출이
	 * 실패했다고 예산이 되돌아가면 같은 행이 영원히 재시도되고, 그러면 상한 3회가 무의미해진다.
	 */
	@Test
	void theRetryBudgetIsSpentEvenWhenTheCallFails() throws Exception {
		long contextId = newContextIdFor("rescan-failing-call", "호출은 실패해도 예산은 줄어든다");
		seedState(contextId, "PENDING", "PENDING", 0, LONG_AGO);
		STUB.reset(FastApiProcessStub.Mode.SERVER_ERROR);

		scheduler.runOnce();

		assertThat(STUB.awaitCall()).as("호출은 나갔고 5xx를 받았다").isNotNull();
		assertThat(stateOf(contextId))
			.as("증가는 호출 전에 커밋됐으므로 실패가 되돌리지 못한다")
			.containsEntry("retry_count", 1);
	}

	/**
	 * 두 단계는 독립 전이한다(명세 7장). <b>한쪽만 {@code PENDING}인 행이 정상적으로 존재하고</b>, 그것도
	 * 재스캔 대상이다. 두 단계를 한 덩어리로 취급하면 Keyword만 밀린 Context가 영원히 방치된다.
	 *
	 * <p>재요청이 나가도 {@code COMPLETED}인 단계는 그대로 남아야 한다 — 어느 단계부터 재개할지는
	 * FastAPI가 판단하고(명세 7장) Spring은 지시하지 않는다.
	 */
	@Test
	void oneStageCompletedAndTheOtherStaleIsStillACandidate() throws Exception {
		long contextId = newContextIdFor("rescan-half-done", "임베딩만 끝난 상태");
		seedState(contextId, "COMPLETED", "PENDING", 0, LONG_AGO);

		scheduler.runOnce();

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).as("Keyword만 밀린 행도 재스캔 대상이다").isNotNull();
		assertThat(call.contextId()).isEqualTo(contextId);
		assertThat(stateOf(contextId))
			.as("끝난 단계를 되돌리지 않는다 — 되돌리면 Embedding을 다시 만들어야 한다")
			.containsEntry("embedding_status", "COMPLETED")
			.containsEntry("keyword_status", "PENDING")
			.containsEntry("retry_count", 1);
	}

	/**
	 * 종결 상태는 후보가 아니다(명세 4.1). {@code FAILED}가 자동으로 되살아나는 경로는 없고,
	 * {@code CANCELLED}는 사용자가 지운 Context이므로 되살리면 삭제한 기록으로 임베딩이 만들어진다.
	 */
	@Test
	void completedFailedAndCancelledRowsAreNeverCandidates() throws Exception {
		long completed = seedSynthetic("COMPLETED", "COMPLETED", 0);
		long failed = seedSynthetic("FAILED", "FAILED", 0);
		long cancelled = seedSynthetic("CANCELLED", "CANCELLED", 0);

		scheduler.runOnce();

		assertThat(STUB.noCallWithin(300)).as("종결된 행으로는 호출이 나가지 않는다").isTrue();
		assertThat(retryCountOf(completed)).isZero();
		assertThat(retryCountOf(failed)).isZero();
		assertThat(retryCountOf(cancelled)).isZero();
		assertThat(stateOf(cancelled))
			.as("CANCELLED를 손대지 않는다")
			.containsEntry("embedding_status", "CANCELLED");
	}

	/**
	 * 만료 임계값은 설정에서 오고 <b>단계 상태별로 다르다</b>(명세 2장). {@code PROCESSING}이 더 긴
	 * 이유는 실제로 처리 중일 가능성을 고려하기 때문이다.
	 *
	 * <p>세 행의 나이를 두 임계값 사이에 배치해 <b>한 회차에서 갈라지는 것</b>을 본다. 임계값이 코드
	 * 상수라면 이 배치가 성립하지 않으므로, 이 테스트가 곧 "설정으로 주입된다"의 확인이다.
	 */
	@Test
	void expiryThresholdsComeFromConfigurationAndDifferByStage() {
		long stalePending = seedSynthetic("COMPLETED", "PENDING", 0, Duration.ofMinutes(3));
		long freshProcessing = seedSynthetic("COMPLETED", "PROCESSING", 0, Duration.ofMinutes(3));
		long staleProcessing = seedSynthetic("COMPLETED", "PROCESSING", 0, Duration.ofMinutes(5));

		scheduler.runOnce();

		assertThat(properties.pendingExpiry()).isEqualTo(PENDING_EXPIRY);
		assertThat(properties.processingExpiry()).isEqualTo(PROCESSING_EXPIRY);
		assertThat(retryCountOf(stalePending)).as("3분 > PENDING 임계 2분").isEqualTo(1);
		assertThat(retryCountOf(freshProcessing)).as("3분 < PROCESSING 임계 4분").isZero();
		assertThat(retryCountOf(staleProcessing)).as("5분 > PROCESSING 임계 4분").isEqualTo(1);
	}

	/**
	 * <b>마지막 재시도는 그 회차에서 종결되지 않는다</b>(명세 3.1·6.1). {@code retry_count = 2}인 만료
	 * 행은 이 회차에서 3이 되고, 3회차 요청이 실제로 나가야 하며 상태는 아직 {@code FAILED}가 아니어야
	 * 한다.
	 *
	 * <p><b>이 단언은 "Finalize가 먼저 돈다"의 프록시가 아니다.</b> 실측에서 확인했다 — {@code runOnce}의
	 * 두 줄을 맞바꿔도 이 테스트는 통과한다. 재시도 증가가 {@code updated_at}을 함께 갱신하므로 그 행이
	 * <b>만료 상태에서 벗어나</b> Finalizer 후보 조건에 걸리지 않기 때문이다. 즉 이 창을 실제로 확보하는
	 * 것은 순서가 아니라 <b>Finalizer의 만료 조건 + 증가 시 {@code updated_at} 갱신</b>이고, 그 둘은
	 * 아래 {@code anExhaustedRowThatIsNotExpiredYetIsLeftAlone}이 고정한다. 순서 자체는 심층 방어이며
	 * {@code AiRescanSchedulerOrderTest}가 호출 순서로 고정한다.
	 */
	@Test
	void theLastRetryActuallyGoesOutAndIsNotFinalizedInTheSameRound() throws Exception {
		long contextId = newContextIdFor("rescan-last-try", "마지막 재시도");
		seedState(contextId, "PENDING", "PENDING", 2, LONG_AGO);

		scheduler.runOnce();

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).as("3회차 요청은 실제로 나가야 한다").isNotNull();
		assertThat(call.contextId()).isEqualTo(contextId);
		assertThat(stateOf(contextId))
			.as("같은 회차에서 종결되면 마지막 재시도가 실행되기도 전에 사망 선고가 된다")
			.containsEntry("retry_count", 3)
			.containsEntry("embedding_status", "PENDING")
			.containsEntry("keyword_status", "PENDING");
	}

	/**
	 * Finalizer 없이는 {@code retry_count >= 3}인 행이 <b>PROCESSING으로 영원히 남는다</b> — 재스캔 후보
	 * 조건에서 빠지기만 할 뿐이다. 상태 지표상 "처리 중"으로 오인되어 장애 관측이 불가능해진다(명세 6장).
	 *
	 * <p>Finalizer는 상태 정리만 하고 FastAPI를 부르지 않는다.
	 */
	@Test
	void retryExhaustedAndExpiredRowsAreFinalizedToFailedWithoutCallingFastApi() throws Exception {
		long contextId = seedSynthetic("PROCESSING", "PENDING", 3);

		scheduler.runOnce();

		assertThat(STUB.noCallWithin(300)).as("Finalizer는 순수한 상태 정리 단계다").isTrue();
		assertThat(stateOf(contextId))
			.containsEntry("embedding_status", "FAILED")
			.containsEntry("keyword_status", "FAILED")
			.containsEntry("retry_count", 3);
	}

	/**
	 * <b>Finalizer에도 만료 조건이 붙는다</b>(명세 6.1). 이것이 마지막 재시도에게 창을 주는 장치다 —
	 * 없으면 {@code retry_count}를 3으로 올린 직후의 행이 다음 회차에서 곧바로 종결되어, 방금 나간
	 * 3회차 요청이 처리될 시간을 갖지 못한다.
	 *
	 * <p>{@code retry_count = 3}이지만 아직 만료되지 않은 행이 그 상태다. 재스캔 후보도 아니고
	 * ({@code retry_count < 3}이 아니다) 종결 대상도 아니어서, 이 회차는 이 행을 <b>건드리지 않아야</b>
	 * 한다. 위 {@code theLastRetryActuallyGoesOutAndIsNotFinalizedInTheSameRound}가 확인하지 못하는
	 * 바로 그 부분을 여기서 본다.
	 */
	@Test
	void anExhaustedRowThatIsNotExpiredYetIsLeftAlone() {
		long recentlyTouched = seedSynthetic("PENDING", "PENDING", 3, Duration.ofMinutes(1));

		scheduler.runOnce();

		assertThat(stateOf(recentlyTouched))
			.as("만료 조건이 없으면 방금 나간 3회차 요청이 처리되기도 전에 사망 선고가 된다")
			.containsEntry("embedding_status", "PENDING")
			.containsEntry("keyword_status", "PENDING")
			.containsEntry("retry_count", 3);
	}

	/**
	 * 종결은 <b>미완료 단계만</b> 건드린다(명세 6.3·6.4).
	 *
	 * <ul>
	 *   <li>{@code COMPLETED}를 지우면 부분 성공이 사라져 Embedding을 다시 만들어야 한다.</li>
	 *   <li><b>{@code CANCELLED}가 우선한다.</b> 후보를 잡은 뒤 UPDATE 직전에 삭제·교체가 끼어들 수
	 *       있으므로 화이트리스트를 블랙리스트로 바꾸면 그 창에서 삭제 표시가 실패로 뒤집힌다.</li>
	 * </ul>
	 */
	@Test
	void theFinalizerKeepsCompletedStagesAndNeverOverwritesCancelled() {
		long halfDone = seedSynthetic("COMPLETED", "PROCESSING", 3);
		long halfCancelled = seedSynthetic("CANCELLED", "PROCESSING", 3);
		long fullyCancelled = seedSynthetic("CANCELLED", "CANCELLED", 3);

		scheduler.runOnce();

		assertThat(stateOf(halfDone))
			.containsEntry("embedding_status", "COMPLETED")
			.containsEntry("keyword_status", "FAILED");
		assertThat(stateOf(halfCancelled))
			.as("삭제 표시가 실패로 뒤집히면 '사용자가 지웠다'와 'AI가 실패했다'를 구별할 수 없다")
			.containsEntry("embedding_status", "CANCELLED")
			.containsEntry("keyword_status", "FAILED");
		assertThat(stateOf(fullyCancelled))
			.as("두 단계 모두 종결 상태라 후보조차 되지 않는다")
			.containsEntry("embedding_status", "CANCELLED")
			.containsEntry("keyword_status", "CANCELLED");
	}

	/**
	 * 삭제된 Context와 수정으로 교체된 구버전은 호출 대상이 아니다(명세 5.1·5.2).
	 *
	 * <p>구 Context의 상태 행을 일부러 {@code PENDING}으로 되돌려 후보가 되게 만든다. 정상 경로라면
	 * 삭제 트랜잭션이 {@code CANCELLED}로 바꿔 두므로 후보조차 되지 않지만, 그러면 <b>호출 직전의 삭제
	 * 확인이 실제로 동작하는지</b>를 볼 수 없다 — 이 재현은 삭제 트랜잭션이 무효화를 빠뜨린 상태
	 * (명세 5.1이 정합성 경고를 남기라고 하는 그 상태)이기도 하다.
	 *
	 * <p>후보로 잡혀 {@code retry_count}는 올라가고 호출만 생략되는 것이 기대 동작이다. 예산을
	 * 소진시키므로 지워진 Context가 3회차 뒤에는 후보에서도 사라진다.
	 */
	@Test
	void deletedAndSupersededContextsAreClaimedButNeverSentToFastApi() throws Exception {
		long memberId = newMemberId();
		RecordCreateResponse created = recordService.create(memberId, createRequest("rescan-deleted", "교체 전 이유"));
		STUB.awaitCall();
		long recordId = created.recordId();
		long oldContextId = onlyContextId(recordId);
		recordService.replaceContext(memberId, recordId, oldContextId, "교체 후 이유");
		STUB.awaitCall();
		assertThat(contextRepository.findById(oldContextId))
			.as("구 Context는 소프트 삭제돼 조회되지 않아야 한다 — 이 테스트의 전제")
			.isEmpty();
		seedState(oldContextId, "PENDING", "PENDING", 0, LONG_AGO);
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);

		scheduler.runOnce();

		assertThat(STUB.noCallWithin(500))
			.as("지워진 Context의 본문을 보내면 삭제된 기록으로 임베딩이 만들어진다")
			.isTrue();
		assertThat(retryCountOf(oldContextId))
			.as("호출만 생략한다 — 후보 선택과 예산 소진은 그대로 일어난다")
			.isEqualTo(1);
	}

	/**
	 * {@code SKIP LOCKED}가 실제로 건너뛰는지 관측한다(명세 4.2). 리더 선출을 두지 않는 근거가 이
	 * 동작이므로, 이것이 깨지면 다중 인스턴스에서 같은 Context가 두 번 처리된다.
	 *
	 * <p>다른 커넥션이 한 행을 {@code FOR UPDATE}로 붙잡은 채 회차를 돌린다. <b>회차를 별 스레드에서
	 * 돌리는 이유</b>: {@code SKIP LOCKED}가 빠지면 후보 조회가 잠금을 기다리며 멈추는데, 같은 스레드에서
	 * 부르면 테스트가 실패하는 대신 영원히 매달린다. 타임아웃을 걸어 <b>실패로</b> 드러나게 한다.
	 */
	@Test
	void skipLockedLeavesALockedRowToWhoeverHoldsIt() throws Exception {
		long locked = seedSynthetic("PENDING", "PENDING", 0);
		long free = seedSynthetic("PENDING", "PENDING", 0);

		try (Connection holder = DriverManager.getConnection(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
			holder.setAutoCommit(false);
			lockRow(holder, locked);

			CompletableFuture<Void> round = CompletableFuture.runAsync(scheduler::runOnce);
			round.get(15, TimeUnit.SECONDS);

			holder.rollback();
		}

		assertThat(retryCountOf(locked))
			.as("잠긴 행은 기다리지 않고 건너뛴다 — 기다리면 배치 전체가 한 행에 묶인다")
			.isZero();
		assertThat(retryCountOf(free))
			.as("건너뛴 것은 잠긴 행뿐이고 회차는 계속 진행한다")
			.isEqualTo(1);
	}

	/**
	 * 스케줄링은 <b>전용 {@link ThreadPoolTaskScheduler}</b>를 쓴다(명세 3장). Boot의 기본 스케줄러는
	 * 단일 스레드이고 앞으로 붙는 모든 배치가 그것을 공유한다.
	 *
	 * <p>{@link TaskScheduler} Bean이 하나뿐인 것까지 단언한다. Spring은 스케줄러를 작업별로 고르지
	 * 않으므로, 두 번째 Bean이 생기면 이름이 {@code taskScheduler}가 아닌 쪽은 조용히 무시되고 최악의
	 * 경우 로컬 단일 스레드 실행자로 떨어진다. 그 변경이 생기면 여기서 먼저 걸린다.
	 */
	@Test
	void schedulingRunsOnADedicatedThreadPoolTaskScheduler() {
		Map<String, TaskScheduler> schedulers = applicationContext.getBeansOfType(TaskScheduler.class);

		assertThat(schedulers)
			.as("Spring은 스케줄러를 작업별로 고르지 않는다 — 유일해야 해석이 흔들리지 않는다")
			.hasSize(1);
		TaskScheduler dedicated = schedulers.values().iterator().next();
		assertThat(dedicated).isInstanceOf(ThreadPoolTaskScheduler.class);
		assertThat(((ThreadPoolTaskScheduler)dedicated).getThreadNamePrefix()).isEqualTo("ai-rescan-");
		assertThat(((ThreadPoolTaskScheduler)dedicated).getPoolSize()).isEqualTo(2);
	}

	/**
	 * {@code @Scheduled(fixedDelayString)}가 읽는 키와 {@link AiRescanProperties}가 읽는 키가 같은
	 * {@code pinlog.ai.rescan.interval}임을 확인한다. 두 곳이 갈라지면 "설정을 바꿨는데 주기가 그대로"가
	 * 된다. 값은 {@code IntegrationContainerSupport}가 테스트용으로 덮은 것이다.
	 *
	 * <p>이 컨텍스트가 떴다는 사실 자체가 애노테이션 쪽 파싱의 확인이기도 하다 — 형식이 맞지 않으면
	 * {@code @Scheduled} 등록 단계에서 기동이 실패한다.
	 */
	@Test
	void theIntervalPropertyBindsIntoTheRecordThatDocumentsIt() {
		assertThat(properties.interval()).isEqualTo(Duration.ofHours(1));
		assertThat(properties.maxRetry())
			.as("정본은 DB의 CHECK (retry_count BETWEEN 0 AND 3)이다")
			.isEqualTo(3);
		assertThat(properties.batchSize()).isEqualTo(100);
	}

	private static void lockRow(Connection holder, long contextId) throws SQLException {
		try (PreparedStatement lock = holder.prepareStatement(
			"SELECT context_id FROM ai.context_ai_state WHERE context_id = ? FOR UPDATE")) {
			lock.setLong(1, contextId);
			try (ResultSet rows = lock.executeQuery()) {
				assertThat(rows.next()).as("잠글 행이 있어야 한다 — 이 테스트의 전제").isTrue();
			}
		}
	}

	private void unstaleEveryStateRow() {
		jdbcTemplate.update("UPDATE ai.context_ai_state SET updated_at = now()");
	}

	private long seedSynthetic(String embeddingStatus, String keywordStatus, int retryCount) {
		return seedSynthetic(embeddingStatus, keywordStatus, retryCount, LONG_AGO);
	}

	private long seedSynthetic(String embeddingStatus, String keywordStatus, int retryCount, Duration age) {
		long contextId = SYNTHETIC_IDS.decrementAndGet();
		seedState(contextId, embeddingStatus, keywordStatus, retryCount, age);
		return contextId;
	}

	private void seedState(long contextId, String embeddingStatus, String keywordStatus,
		int retryCount, Duration age) {
		jdbcTemplate.update(SEED_SQL, contextId, embeddingStatus, keywordStatus, retryCount,
			(double)age.toSeconds());
	}

	/** Record를 만들고 접수 호출까지 소진한 뒤 그 Context id를 준다. */
	private long newContextIdFor(String kakaoPlaceId, String body) throws InterruptedException {
		RecordCreateResponse created = recordService.create(newMemberId(), createRequest(kakaoPlaceId, body));
		assertThat(STUB.awaitCall()).as("생성 시 접수 호출이 먼저 일어난다 — 이 테스트의 전제").isNotNull();
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);
		return onlyContextId(created.recordId());
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long onlyContextId(Long recordId) {
		List<Context> contexts = contextRepository.findByRecordIdOrderByOriginCreatedAtAscIdAsc(recordId);
		assertThat(contexts).hasSize(1);
		return contexts.get(0).getId();
	}

	private int retryCountOf(long contextId) {
		return (int)stateOf(contextId).get("retry_count");
	}

	private Map<String, Object> stateOf(long contextId) {
		return jdbcTemplate.queryForMap(
			"SELECT embedding_status, keyword_status, retry_count FROM ai.context_ai_state WHERE context_id = ?",
			contextId);
	}

	private RecordCreateRequest createRequest(String kakaoPlaceId, String body) {
		return new RecordCreateRequest(
			new PlacePayload(
				kakaoPlaceId,
				"앤트러사이트 성수",
				"서울 성동구 성수이로 7길 30",
				"서울 성동구 성수이로7길 30",
				null,
				null,
				new BigDecimal("37.5445000"),
				new BigDecimal("127.0557000")
			),
			body);
	}
}
