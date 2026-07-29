package com.pinlog.pinlogback.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.record.dto.ContextMutationResponse;
import com.pinlog.pinlogback.domain.record.dto.PlacePayload;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateRequest;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateResponse;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.service.RecordService;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * Context 생성·교체가 {@code ai.context_ai_state}에 {@code PENDING}을 남기고 FastAPI를 부르는지 —
 * 그리고 <b>그 둘의 순서</b>가 지켜지는지 확인한다(S15P11A705-102).
 *
 * <p><b>이 클래스는 트랜잭션 롤백 테스트가 아니다.</b> {@code @Transactional} 테스트는 커밋하지
 * 않으므로 대역이 별 커넥션으로 조회할 때 애초에 아무 것도 보이지 않는다. 그러면 "호출 시점에
 * 이미 커밋돼 있었다"는 명제를 참으로도 거짓으로도 만들 수 없어 검증이 성립하지 않는다. 실제로
 * 커밋시키고, 남는 행은 테스트마다 다른 {@code kakaoPlaceId}로 격리한다.
 */
@SpringBootTest
class ContextAiEnqueueTests extends IntegrationContainerSupport {

	/**
	 * Spring Context보다 먼저 떠야 {@code @DynamicPropertySource}가 포트를 알 수 있다.
	 * 상위 클래스의 static 초기화가 먼저 도므로 이 시점에 컨테이너는 이미 기동해 있다.
	 */
	private static final FastApiProcessStub STUB = new FastApiProcessStub(POSTGRES);

	private static final String INTERNAL_SECRET = "test-internal-secret";

	@DynamicPropertySource
	static void aiServerPointsAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
		registry.add("pinlog.ai.internal-secret", () -> INTERNAL_SECRET);
	}

	@Autowired
	private RecordService recordService;

	@Autowired
	private ContextRepository contextRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void resetStub() {
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);
	}

	/**
	 * 이 PR의 핵심 단언이다. 대역의 <b>요청 핸들러 안에서</b> 별 커넥션으로 조회한 결과가 곧
	 * "호출이 도착한 그 시점의 DB 상태"다. 순서를 뒤집어 트랜잭션 안에서 호출하면
	 * {@code stateVisible}이 거짓이 되어 이 테스트가 깨진다.
	 */
	@Test
	void processIsCalledOnlyAfterThePendingRowIsAlreadyCommitted() throws Exception {
		long memberId = newMemberId();

		RecordCreateResponse created = recordService.create(memberId, createRequest("ai-enqueue-1", "비 오는 날 가려고 저장"));

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).as("커밋 이후 FastAPI 호출이 발생해야 한다").isNotNull();
		assertThat(call.stateVisible())
			.as("호출이 도착한 시점에 PENDING 행이 별 커넥션에서 이미 보여야 한다 — 워커는 별 프로세스라 미커밋을 볼 수 없다")
			.isTrue();
		assertThat(call.embeddingStatus()).isEqualTo("PENDING");
		assertThat(call.keywordStatus()).isEqualTo("PENDING");
		assertThat(call.contextId()).isEqualTo(onlyContextId(created.recordId()));
		assertThat(call.text()).isEqualTo("비 오는 날 가려고 저장");
		assertThat(call.internalSecret())
			.as("내부 호출은 공유 시크릿 헤더 없이는 401이다")
			.isEqualTo(INTERNAL_SECRET);
	}

	@Test
	void recordCreationLeavesExactlyOnePendingRowWithFullRetryBudget() throws Exception {
		long memberId = newMemberId();

		RecordCreateResponse created = recordService.create(memberId, createRequest("ai-enqueue-2", "커피가 좋아서"));
		STUB.awaitCall();

		Map<String, Object> state = stateOf(onlyContextId(created.recordId()));
		assertThat(state).containsEntry("embedding_status", "PENDING").containsEntry("keyword_status", "PENDING");
		assertThat(state.get("retry_count"))
			.as("재시도 예산은 context_id 단위다 — 새 Context는 언제나 3회를 새로 갖는다")
			.isEqualTo(0);
	}

	@Test
	void addingAContextToAnExistingRecordAlsoEnqueuesIt() throws Exception {
		long memberId = newMemberId();
		RecordCreateResponse created = recordService.create(memberId, createRequest("ai-enqueue-3", "첫 번째 이유"));
		STUB.awaitCall();
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);

		ContextMutationResponse added = recordService.addContext(memberId, created.recordId(), "두 번째 이유");

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).isNotNull();
		assertThat(call.contextId()).isEqualTo(added.contextId());
		assertThat(call.stateVisible()).isTrue();
		assertThat(call.text()).isEqualTo("두 번째 이유");
	}

	/**
	 * <b>Context가 하나뿐인 상태에서 수행한다.</b> "마지막 Context는 삭제할 수 없다"는 가드는 마지막
	 * 1건일 때만 의미가 있으므로, 2개 이상 남겨 두고 검증하면 순서가 뒤집혀도 아무 일이 일어나지
	 * 않아 이 테스트가 순서의 프록시 노릇을 못 한다.
	 *
	 * <p>구 Context의 CANCELLED 전이는 이 PR의 범위가 아니다(back#80). 구 상태 행이 그대로
	 * {@code PENDING}인 것까지 단언해 경계를 못박는다 — 여기서 손대기 시작하면 삭제 경로가 두 곳이 된다.
	 */
	@Test
	void replacingTheOnlyContextEnqueuesTheNewOneAndLeavesTheOldStateToTheDeletionPath() throws Exception {
		long memberId = newMemberId();
		RecordCreateResponse created = recordService.create(memberId, createRequest("ai-enqueue-4", "고치기 전 이유"));
		STUB.awaitCall();
		long recordId = created.recordId();
		long oldContextId = onlyContextId(recordId);
		assertThat(contextRepository.countByRecordId(recordId))
			.as("가드가 의미를 갖는 유일한 상태 — Context가 정확히 1개")
			.isEqualTo(1);
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);

		ContextMutationResponse replaced = recordService.replaceContext(memberId, recordId, oldContextId, "고친 이유");

		assertThat(replaced.contextId()).isNotEqualTo(oldContextId);
		assertThat(contextRepository.countByRecordId(recordId))
			.as("교체 후에도 활성 Context는 1개다 — 0이 되는 중간 상태가 없다")
			.isEqualTo(1);
		assertThat(onlyContextId(recordId)).isEqualTo(replaced.contextId());

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).as("신 context_id로만 호출한다").isNotNull();
		assertThat(call.contextId()).isEqualTo(replaced.contextId());
		assertThat(call.stateVisible()).isTrue();
		assertThat(call.text()).isEqualTo("고친 이유");

		assertThat(stateOf(replaced.contextId()))
			.containsEntry("embedding_status", "PENDING")
			.containsEntry("keyword_status", "PENDING");
		assertThat(stateOf(oldContextId))
			.as("구 Context의 CANCELLED 전이는 삭제 경로(back#80)의 몫이다")
			.containsEntry("embedding_status", "PENDING");
	}

	/**
	 * FastAPI가 5xx를 돌려줘도 Core와 PENDING은 남아야 한다. 롤백시키면 사용자가 저장한 기록이
	 * <b>상대 서버 장애 때문에</b> 사라진다.
	 */
	@Test
	void serverErrorFromFastApiDoesNotRollBackTheContextOrItsPendingRow() throws Exception {
		STUB.reset(FastApiProcessStub.Mode.SERVER_ERROR);
		long memberId = newMemberId();

		RecordCreateResponse created = recordService.create(memberId, createRequest("ai-enqueue-5", "5xx여도 남아야 한다"));

		assertThat(STUB.awaitCall()).isNotNull();
		long contextId = onlyContextId(created.recordId());
		assertThat(contextRepository.findById(contextId)).isPresent();
		assertThat(stateOf(contextId))
			.as("PENDING이 남아야 재스캔이 나중에 주울 수 있다")
			.containsEntry("embedding_status", "PENDING");
	}

	/** 연결이 끊기는 실패(다운·타임아웃 계열)도 같다. 클라이언트가 예외를 삼키지 않으면 여기서 드러난다. */
	@Test
	void droppedConnectionDoesNotRollBackTheContextOrItsPendingRow() throws Exception {
		STUB.reset(FastApiProcessStub.Mode.HANG_UP);
		long memberId = newMemberId();

		RecordCreateResponse created = recordService.create(memberId, createRequest("ai-enqueue-6", "끊겨도 남아야 한다"));

		assertThat(STUB.awaitCall()).isNotNull();
		long contextId = onlyContextId(created.recordId());
		assertThat(contextRepository.findById(contextId)).isPresent();
		assertThat(stateOf(contextId)).containsEntry("embedding_status", "PENDING");
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long onlyContextId(Long recordId) {
		List<Context> contexts = contextRepository.findByRecordIdOrderByOriginCreatedAtAscIdAsc(recordId);
		assertThat(contexts).hasSize(1);
		return contexts.get(0).getId();
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
