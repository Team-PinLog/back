package com.pinlog.pinlogback.domain.ai.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pinlog.pinlogback.domain.ai.client.AiProcessClient;
import com.pinlog.pinlogback.domain.ai.client.ContextProcessRequest;
import com.pinlog.pinlogback.domain.ai.repository.ContextAiStateRow;
import com.pinlog.pinlogback.domain.ai.service.AiFailedFinalizer;
import com.pinlog.pinlogback.domain.ai.service.AiRescanCandidateService;
import com.pinlog.pinlogback.domain.ai.service.ContextProcessRequestAssembler;

/**
 * 유실·정지된 AI 처리를 복구하는 한 회차
 * (AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 3.1).
 *
 * <p><b>이 클래스가 없으면 한 번 실패한 Context는 영구히 {@code PENDING}으로 남는다.</b> 실패 경로
 * 네 곳이 모두 "재스캔이 복구한다"를 안전망으로 전제한다 — 큐 포화로 버려진 요청
 * ({@link com.pinlog.pinlogback.domain.ai.AiIntegrationConfig}), 삼켜진 호출 실패
 * ({@link AiProcessClient}), 커밋 이후 리스너의 조립 실패
 * ({@link com.pinlog.pinlogback.domain.ai.event.ContextAiRequestedListener}), 그리고 FastAPI가 202
 * 이후 내부에서 실패한 경우. 상태만 보면 "처리 대기 중"이라 정상과 구별되지 않는 것이 이 문제의
 * 성질이다.
 *
 * <p><b>순서가 계약이다.</b> {@code Finalize → 후보 잠금·retry 증가 → 커밋 → Context 재조회 → 삭제
 * 확인 → FastAPI 호출}. Finalize를 먼저 두는 이유는 나중에 두면 같은 회차에서 방금
 * {@code retry_count}를 3으로 올린 행을 곧바로 {@code FAILED}로 종결해, 마지막 재시도가 실행되기도
 * 전에 사망 선고를 내리기 때문이다(명세 3.1·6.1).
 *
 * <p>커밋 경계와 외부 호출이 갈리는 지점은 {@link AiRescanCandidateService#claimStale()}의 반환이다.
 * 이 클래스 자체는 트랜잭션을 열지 않는다 — 열면 뒤의 HTTP 호출 시간만큼 행 잠금이 붙잡힌다.
 */
@Component
public class AiRescanScheduler {

	private static final Logger log = LoggerFactory.getLogger(AiRescanScheduler.class);

	private static final String CANCELLED = "CANCELLED";

	private final AiFailedFinalizer finalizer;
	private final AiRescanCandidateService candidates;
	private final ContextProcessRequestAssembler assembler;
	private final AiProcessClient client;

	public AiRescanScheduler(AiFailedFinalizer finalizer, AiRescanCandidateService candidates,
		ContextProcessRequestAssembler assembler, AiProcessClient client) {
		this.finalizer = finalizer;
		this.candidates = candidates;
		this.assembler = assembler;
		this.client = client;
	}

	/**
	 * 한 회차.
	 *
	 * <p><b>{@code fixedRate}가 아니라 {@code fixedDelay}다</b>(명세 3장). 한 회차가 배치 크기만큼의
	 * HTTP 호출을 순차로 내보내므로 실행 시간이 주기를 넘길 수 있고, {@code fixedRate}면 그때 다음
	 * 회차가 겹쳐 돈다. 겹쳐도 {@code SKIP LOCKED} 덕에 같은 행을 두 번 집지는 않지만, 밀린 회차가
	 * 계속 쌓여 FastAPI에 부하를 더한다.
	 *
	 * <p>주기 값을 애노테이션에 문자열로 두는 것은 {@code @Scheduled}의 제약이다. 타입 있는 값은
	 * {@link com.pinlog.pinlogback.domain.ai.service.AiRescanProperties#interval()}에 같은 키로 있다.
	 *
	 * <p>예외를 잡지 않는다. Spring의 기본 오류 처리기가 로그를 남기고 {@code fixedDelay} 일정은
	 * 유지되므로, 여기서 삼키면 실패가 회차 집계 로그에 "0건 처리"로 위장될 뿐이다.
	 */
	@Scheduled(fixedDelayString = "${pinlog.ai.rescan.interval}")
	public void runOnce() {
		int finalized = finalizer.finalizeExpired().size();
		List<ContextAiStateRow> claimed = candidates.claimStale();
		int requested = 0;
		for (ContextAiStateRow candidate : claimed) {
			if (requestProcessing(candidate)) {
				requested++;
			}
		}
		if (finalized > 0 || !claimed.isEmpty()) {
			log.info("AI 재스캔 회차 종료: FAILED 종결 {}건, 후보 {}건, 재요청 {}건",
				finalized, claimed.size(), requested);
		}
	}

	/**
	 * 명세 5장의 {@code 재조회 → 삭제 확인 → 호출}.
	 *
	 * <p><b>이전 요청 본문을 보관했다가 재전송하지 않는다.</b> Context는 불변이라 재조회한 본문은 첫
	 * 시도와 같은데도 다시 읽는 이유는 본문을 얻기 위해서가 아니라 <b>그 Context가 아직 살아 있는지</b>
	 * 확인하기 위해서다(명세 5.1). 조립기가 소프트 삭제된 Context를 걸러 내므로, 삭제된 것과 수정으로
	 * 교체된 구버전이 같은 경로로 함께 빠진다 — 둘을 구분할 필요가 없다.
	 *
	 * <p>조립 실패를 삼키는 이유는 {@code ContextAiRequestedListener}와 같다. 한 후보의 DB 조회 실패로
	 * 나머지 후보까지 버리면 회차 전체가 한 행에 묶인다. {@code retry_count}는 이미 커밋됐으므로
	 * 이 실패로 예산이 되돌아가지도 않는다.
	 *
	 * @return FastAPI에 요청을 보냈는가
	 */
	private boolean requestProcessing(ContextAiStateRow candidate) {
		long contextId = candidate.contextId();
		try {
			ContextProcessRequest request = assembler.assemble(contextId).orElse(null);
			if (request == null) {
				warnUnlessCancelled(contextId);
				return false;
			}
			client.process(request);
			return true;
		} catch (RuntimeException e) {
			log.warn("재스캔 요청 조립 실패: contextId={}, cause={}", contextId, e.toString());
			return false;
		}
	}

	/**
	 * Context가 사라졌는데 상태가 {@code CANCELLED}가 아니면 정합성 경고를 남긴다(명세 5.1). 삭제·수정
	 * 트랜잭션이 {@code CANCELLED} 기록을 빠뜨렸다는 뜻이고, 그대로 두면 늦게 도착한 FastAPI 결과가
	 * 지워진 Context에 저장된다 — {@code CANCELLED}가 막아야 할 바로 그 누출이다.
	 *
	 * <p>두 단계 중 <b>하나라도</b> {@code CANCELLED}가 아니면 경고한다. 무효화는 두 컬럼을 함께
	 * 쓰므로({@code AiDerivedDataRepository}) 한쪽만 남았다는 것도 같은 종류의 누락이다.
	 */
	private void warnUnlessCancelled(long contextId) {
		ContextAiStateRow current = candidates.findCurrentState(contextId).orElse(null);
		if (current == null) {
			log.warn("재스캔 후보의 상태 행이 사라졌다: contextId={}. 상태 행 없이는 이 Context를 "
				+ "다시 집을 수도 없다", contextId);
			return;
		}
		if (CANCELLED.equals(current.embeddingStatus()) && CANCELLED.equals(current.keywordStatus())) {
			log.debug("이미 삭제된 Context라 재스캔 호출을 생략한다: contextId={}", contextId);
			return;
		}
		log.warn("삭제된 Context의 상태가 CANCELLED가 아니다(삭제·수정 트랜잭션이 무효화를 빠뜨렸다): "
				+ "contextId={}, embedding={}, keyword={}",
			contextId, current.embeddingStatus(), current.keywordStatus());
	}
}
