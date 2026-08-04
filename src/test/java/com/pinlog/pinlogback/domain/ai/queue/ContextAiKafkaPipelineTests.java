package com.pinlog.pinlogback.domain.ai.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pinlog.pinlogback.domain.ai.FastApiProcessStub;
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
 * Record 저장 → Kafka 발행 → back 컨슈머 → FastAPI 호출이 완주하는지 확인한다(BD-48).
 *
 * <p>{@code ContextAiEnqueueTests}가 "커밋과 호출의 순서"를 고정한다면, 이 클래스는 <b>전달 수단이
 * 브로커를 경유한다는 사실</b>을 고정한다 — 인메모리 큐 시절에는 프로세스가 죽으면 힌트가 사라졌지만,
 * 이제 발행까지만 성공하면 소비는 브로커가 보증한다. 실패 경로(재시도 체인·DLT)와 멱등 가드는
 * 뒤 태스크에서 이 클래스에 추가된다.
 */
@SpringBootTest
class ContextAiKafkaPipelineTests extends IntegrationContainerSupport {

	private static final FastApiProcessStub STUB = new FastApiProcessStub(POSTGRES);

	@DynamicPropertySource
	static void aiServerPointsAtTheStub(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
	}

	@Autowired
	private RecordService recordService;

	@Autowired
	private ContextRepository contextRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@BeforeEach
	void resetStub() {
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);
	}

	@AfterAll
	static void stopStub() {
		STUB.stop();
	}

	@Test
	void messageOnTheTopicReachesFastApiThroughTheConsumer() throws Exception {
		long memberId = newMemberId();

		recordService.create(memberId, createRequest("kafka-pipe-1", "카프카로 간다"));

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).as("발행→소비→HTTP 경로가 완주해야 한다").isNotNull();
		assertThat(call.stateVisible())
			.as("PENDING 커밋 후에만 발행되므로 소비 시점에는 반드시 보인다")
			.isTrue();
		assertThat(call.text()).isEqualTo("카프카로 간다");
	}

	/**
	 * 5xx는 재시도 체인을 소진한 뒤 DLT로 격리된다. 시도 횟수는
	 * {@code IntegrationContainerSupport}가 3으로 줄여 두었다(본 토픽 1 + 재시도 토픽 2).
	 */
	@Test
	void serverErrorsExhaustTheRetryChainAndLandInTheDlt() throws Exception {
		STUB.reset(FastApiProcessStub.Mode.SERVER_ERROR);
		long memberId = newMemberId();

		long recordId = recordService.create(memberId, createRequest("kafka-dlt-1", "5xx는 재시도 후 DLT")).recordId();

		assertThat(STUB.awaitCall()).as("1차 시도").isNotNull();
		assertThat(STUB.awaitCall()).as("재시도 1").isNotNull();
		assertThat(STUB.awaitCall()).as("재시도 2 — attempts=3의 마지막").isNotNull();
		assertThat(dltReceivesMessageFor(onlyContextId(recordId), Duration.ofSeconds(15)))
			.as("소진된 메시지는 DLT로 격리된다")
			.isTrue();
	}

	/** 4xx는 요청 자체의 문제라 몇 번을 다시 보내도 같다 — 재시도 없이 DLT로 직행한다. */
	@Test
	void badRequestGoesStraightToTheDltWithoutRetry() throws Exception {
		STUB.reset(FastApiProcessStub.Mode.BAD_REQUEST);
		long memberId = newMemberId();

		long recordId = recordService.create(memberId, createRequest("kafka-dlt-2", "4xx는 재시도 없이 DLT")).recordId();

		assertThat(STUB.awaitCall()).as("한 번은 호출된다").isNotNull();
		assertThat(dltReceivesMessageFor(onlyContextId(recordId), Duration.ofSeconds(15))).isTrue();
		assertThat(STUB.noCallWithin(1000)).as("4xx는 재시도하지 않는다").isTrue();
	}

	/**
	 * 같은 메시지가 두 번 와도 처리 단계를 이미 지난 Context에는 힌트를 또 보내지 않는다.
	 * at-least-once 전달에서 중복은 결함이 아니라 전제다 — 걸러 내는 근거는 브로커가 아니라
	 * 진실의 원본인 {@code ai.context_ai_state}다.
	 */
	@Test
	void duplicateDeliveryForAnAlreadyHandledContextIsSkipped() throws Exception {
		long memberId = newMemberId();
		RecordCreateResponse created = recordService.create(memberId, createRequest("kafka-dup-1", "중복은 걸러진다"));
		assertThat(STUB.awaitCall()).isNotNull();
		long contextId = onlyContextId(created.recordId());
		jdbcTemplate.update(
			"UPDATE ai.context_ai_state SET embedding_status = 'COMPLETED', keyword_status = 'COMPLETED' "
				+ "WHERE context_id = ?",
			contextId);
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);

		kafkaTemplate.send("context-ai.process", Long.toString(contextId),
			new ContextAiProcessMessage(contextId, memberId, created.recordId()).toJson()).get();

		assertThat(STUB.noCallWithin(2000))
			.as("이미 처리 단계를 지난 Context에 힌트를 또 보내지 않는다")
			.isTrue();
	}

	/** 삭제된 Context의 메시지는 실패가 아니라 정상 생략이다 — 재시도도 DLT 격리도 일어나지 않는다. */
	@Test
	void messageForADeletedContextCompletesWithoutCallOrDlt() throws Exception {
		long memberId = newMemberId();
		RecordCreateResponse created = recordService.create(memberId, createRequest("kafka-del-1", "교체 전"));
		assertThat(STUB.awaitCall()).isNotNull();
		long recordId = created.recordId();
		long oldContextId = onlyContextId(recordId);
		recordService.replaceContext(memberId, recordId, oldContextId, "교체 후");
		assertThat(STUB.awaitCall()).isNotNull();
		STUB.reset(FastApiProcessStub.Mode.ACCEPTED);

		kafkaTemplate.send("context-ai.process", Long.toString(oldContextId),
			new ContextAiProcessMessage(oldContextId, memberId, recordId).toJson()).get();

		assertThat(STUB.noCallWithin(2000)).as("삭제된 Context는 호출을 생략한다").isTrue();
		assertThat(dltReceivesMessageFor(oldContextId, Duration.ofSeconds(3)))
			.as("정상 생략이지 실패가 아니므로 DLT로 가지 않는다")
			.isFalse();
	}

	/**
	 * 자기만의 그룹으로 DLT를 처음부터 읽되, <b>이 테스트의 contextId만 자기 것으로 친다.</b>
	 * {@code earliest}로 읽으면 앞 테스트가 격리시킨 메시지도 함께 오므로, 대조 없이 "무언가 왔다"로
	 * 판정하면 앞 테스트의 잔여물로도 통과해 버린다.
	 */
	private boolean dltReceivesMessageFor(long contextId, Duration timeout) {
		Map<String, Object> props = Map.of(
			ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
			ConsumerConfig.GROUP_ID_CONFIG, "dlt-probe-" + UUID.randomUUID(),
			ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
			ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
			ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		long deadline = System.nanoTime() + timeout.toNanos();
		try (KafkaConsumer<String, String> probe = new KafkaConsumer<>(props)) {
			probe.subscribe(List.of("context-ai.process-dlt"));
			while (System.nanoTime() < deadline) {
				for (ConsumerRecord<String, String> record : probe.poll(Duration.ofMillis(500))) {
					if (ContextAiProcessMessage.fromJson(record.value()).contextId() == contextId) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long onlyContextId(Long recordId) {
		List<Context> contexts = contextRepository.findByRecordIdOrderByOriginCreatedAtAscIdAsc(recordId);
		assertThat(contexts).hasSize(1);
		return contexts.get(0).getId();
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
