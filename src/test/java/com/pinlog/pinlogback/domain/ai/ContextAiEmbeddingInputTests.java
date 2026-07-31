package com.pinlog.pinlogback.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.record.dto.PlacePayload;
import com.pinlog.pinlogback.domain.record.dto.RecordCreateRequest;
import com.pinlog.pinlogback.domain.record.service.RecordService;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * {@code pinlog.ai.embedding-input.include-place-name}을 켰을 때 결합된 {@code text}가 실제로
 * <b>전선까지</b> 나가는지 확인한다(임베딩 4조건 측정의 조건 B·D).
 *
 * <p>결합 규칙 자체는 {@code EmbeddingInputComposerTest}가 Spring 없이 검증한다. 여기서 확인하는 것은
 * 그 규칙이 아니라 <b>배선</b>이다 — 설정이 {@code EmbeddingInputProperties}에 바인딩되고,
 * {@code ContextProcessRequestAssembler}가 Place를 읽어 결합하며, 그 결과가 요청 본문의
 * {@code text}로 실린다는 사슬. 조립기까지만 검증하면 이 사슬 중 어디가 끊겨도 통과한다.
 *
 * <p><b>기본값(끔)의 대칭 검증은 {@link ContextAiEnqueueTests}에 이미 있다</b> — 그쪽 여러 테스트가
 * {@code call.text()}를 본문 리터럴과 그대로 비교하므로, 기본값이 조건 A를 재현하지 못하게 되면
 * 이 클래스가 아니라 그쪽이 먼저 깨진다. 같은 단언을 여기 복제하지 않는 이유다.
 *
 * <p>대역·컨테이너 운용은 {@link ContextAiEnqueueTests}와 같다. 설정이 다르면 Spring Context도
 * 달라지므로 클래스를 나눈다.
 */
@SpringBootTest
class ContextAiEmbeddingInputTests extends IntegrationContainerSupport {

	private static final FastApiProcessStub STUB = new FastApiProcessStub(POSTGRES);

	private static final String PLACE_NAME = "동교어린이공원";
	private static final String BODY = "그네팟 스팟. 밥먹고 산책하면서 여기 머물다가 가기 좋음";

	@DynamicPropertySource
	static void placeNameIsJoinedIntoTheEmbeddingInput(DynamicPropertyRegistry registry) {
		registry.add("pinlog.ai.base-url", STUB::baseUrl);
		registry.add("pinlog.ai.internal-secret", () -> "test-internal-secret");
		registry.add("pinlog.ai.embedding-input.include-place-name", () -> true);
	}

	@Autowired
	private RecordService recordService;

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

	/**
	 * 기대 문자열을 리터럴로 적는다. 프로덕션 코드의 구분자 상수를 참조하면 그 상수가 바뀔 때 기대값이
	 * 함께 따라가 <b>아무것도 잡지 못한다</b> — 조건 B·D의 입력 형식은 측정 명세가 고정한 것이라
	 * 코드가 아니라 여기가 정본 노릇을 해야 한다.
	 */
	@Test
	void theProcessCallCarriesThePlaceNameJoinedInFrontOfTheBody() throws Exception {
		long memberId = memberRepository.save(Member.create()).getId();

		recordService.create(memberId, createRequest("ai-embedding-input-1"));

		FastApiProcessStub.Received call = STUB.awaitCall();
		assertThat(call).as("커밋 이후 FastAPI 호출이 발생해야 한다").isNotNull();
		assertThat(call.text())
			.as("조건 B·D의 입력 형식은 \"장소명. 본문\"이다")
			.isEqualTo("동교어린이공원. 그네팟 스팟. 밥먹고 산책하면서 여기 머물다가 가기 좋음");
	}

	private RecordCreateRequest createRequest(String kakaoPlaceId) {
		return new RecordCreateRequest(
			new PlacePayload(
				kakaoPlaceId,
				PLACE_NAME,
				"서울 마포구 동교동 165-8",
				"서울 마포구 잔다리로 60",
				null,
				null,
				new BigDecimal("37.5570000"),
				new BigDecimal("126.9250000")
			),
			BODY);
	}
}
