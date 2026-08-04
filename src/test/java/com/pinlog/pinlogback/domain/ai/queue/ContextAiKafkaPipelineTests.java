package com.pinlog.pinlogback.domain.ai.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pinlog.pinlogback.domain.ai.FastApiProcessStub;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.record.dto.PlacePayload;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateRequest;
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
