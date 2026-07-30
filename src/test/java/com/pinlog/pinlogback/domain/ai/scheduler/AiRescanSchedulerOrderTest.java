package com.pinlog.pinlogback.domain.ai.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import com.pinlog.pinlogback.domain.ai.client.AiProcessClient;
import com.pinlog.pinlogback.domain.ai.service.AiFailedFinalizer;
import com.pinlog.pinlogback.domain.ai.service.AiRescanCandidateService;
import com.pinlog.pinlogback.domain.ai.service.ContextProcessRequestAssembler;

/**
 * 회차의 <b>단계 순서</b>와 <b>스케줄 방식</b>을 대역·리플렉션으로 고정한다(AI 파트 소유 명세
 * {@code docs/ai/spec/ai-rescan-scheduler.md} 3장).
 *
 * <p>{@code AiRescanSchedulerTests}가 같은 두 계약을 <b>관측 가능한 결과</b>로도 고정한다 — 그쪽이
 * 더 강한 검증이다. 여기를 따로 두는 이유는 두 가지다. 순서 쪽은 결과가 아니라 <b>호출 순서 자체</b>를
 * 남겨 두어야 "왜 Finalize가 먼저인가"를 잃지 않고, 스케줄 방식 쪽은 {@code fixedRate}로 바뀌었을 때
 * DB 결과로는 아무 차이가 나지 않아 결과 검증으로 잡을 수 없다.
 */
@ExtendWith(MockitoExtension.class)
class AiRescanSchedulerOrderTest {

	@Mock
	private AiFailedFinalizer finalizer;

	@Mock
	private AiRescanCandidateService candidates;

	@Mock
	private ContextProcessRequestAssembler assembler;

	@Mock
	private AiProcessClient client;

	/**
	 * Finalize가 후보 선택보다 먼저 돈다. 뒤에 두면 같은 회차에서 방금 {@code retry_count}를 3으로 올린
	 * 행을 곧바로 {@code FAILED}로 종결해, 마지막 재시도가 실행되기도 전에 사망 선고를 내린다(명세 3.1).
	 */
	@Test
	void finalizeRunsBeforeTheCandidateClaimInEveryRound() {
		when(finalizer.finalizeExpired()).thenReturn(List.of());
		when(candidates.claimStale()).thenReturn(List.of());
		AiRescanScheduler scheduler = new AiRescanScheduler(finalizer, candidates, assembler, client);

		scheduler.runOnce();

		InOrder order = inOrder(finalizer, candidates);
		order.verify(finalizer).finalizeExpired();
		order.verify(candidates).claimStale();
		verifyNoInteractions(client);
	}

	/**
	 * {@code fixedRate}가 아니라 {@code fixedDelay}다(명세 3장). 한 회차가 배치 크기만큼의 HTTP 호출을
	 * 순차로 내보내므로 실행이 주기를 넘길 수 있고, {@code fixedRate}면 그때 회차가 겹쳐 돈다.
	 *
	 * <p>주기를 리터럴이 아니라 placeholder로 두는 것도 함께 고정한다. 값을 애노테이션에 박으면
	 * {@code pinlog.ai.rescan.interval} 설정이 있어도 아무 효력이 없다.
	 */
	@Test
	void theRoundIsScheduledWithFixedDelayAndReadsTheConfiguredInterval() throws Exception {
		Scheduled scheduled = AiRescanScheduler.class.getMethod("runOnce").getAnnotation(Scheduled.class);

		assertThat(scheduled).as("@Scheduled가 없으면 이 회차는 아무도 돌리지 않는다").isNotNull();
		assertThat(scheduled.fixedDelayString()).isEqualTo("${pinlog.ai.rescan.interval}");
		assertThat(scheduled.fixedRateString())
			.as("fixedRate면 이전 회차가 길어졌을 때 다음 회차가 겹쳐 돈다")
			.isEmpty();
		assertThat(scheduled.fixedRate()).isEqualTo(-1);
		assertThat(scheduled.cron()).isEmpty();
	}
}
