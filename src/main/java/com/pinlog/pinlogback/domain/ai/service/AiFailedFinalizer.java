package com.pinlog.pinlogback.domain.ai.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.ai.repository.AiRescanStateRepository;
import com.pinlog.pinlogback.domain.ai.repository.ContextAiStateRow;

/**
 * 재시도를 소진한 만료 작업의 미완료 단계를 {@code FAILED}로 종결한다
 * (AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 6장).
 *
 * <p><b>선택 사항이 아니다.</b> {@code process} 호출의 응답은 {@code 202 Accepted}이고 그것은 접수만
 * 뜻한다. 완료 통보용 웹훅이 없으므로 202 이후 FastAPI 내부에서 일어난 실패를 Spring은 알 수 없고,
 * {@code retry_count >= 3}인 행은 재스캔 후보 조건({@code retry_count < 3})에서 제외되기만 할 뿐
 * <b>PROCESSING으로 영원히 남는다.</b> 그러면 상태 지표상 "처리 중"으로 오인되어 장애 관측 자체가
 * 불가능해진다.
 *
 * <p><b>FastAPI를 호출하지 않는다.</b> 순수한 상태 정리 단계다.
 *
 * <p>{@link com.pinlog.pinlogback.domain.ai.scheduler.AiRescanScheduler}와 별 Bean인 이유는
 * {@code @Transactional}이 프록시로 걸리기 때문이다. 스케줄러가 자기 메서드를 직접 부르면 프록시를
 * 지나지 않아 트랜잭션이 열리지 않고, 그러면 {@code FOR UPDATE}가 잡은 잠금이 조회 직후 풀려
 * 다중 인스턴스에서 같은 행을 두 번 종결한다.
 */
@Service
public class AiFailedFinalizer {

	private static final Logger log = LoggerFactory.getLogger(AiFailedFinalizer.class);

	private final AiRescanStateRepository repository;
	private final AiRescanProperties properties;

	public AiFailedFinalizer(AiRescanStateRepository repository, AiRescanProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	/**
	 * 후보를 잠그고 미완료 단계를 종결한다. <b>스케줄러 회차의 첫 단계로 부른다</b>(명세 3.1) —
	 * 나중에 두면 같은 회차에서 방금 {@code retry_count}를 3으로 올린 행을 곧바로 종결해, 마지막
	 * 재시도가 실행되기도 전에 사망 선고를 내린다.
	 *
	 * @return 종결한 행들의 <b>종결 직전</b> 상태. 로그와 회차 집계에 쓴다
	 */
	@Transactional
	public List<ContextAiStateRow> finalizeExpired() {
		List<ContextAiStateRow> exhausted = repository.lockRetryExhausted(
			properties.pendingExpiry(), properties.processingExpiry(),
			properties.maxRetry(), properties.batchSize());
		if (exhausted.isEmpty()) {
			return List.of();
		}
		repository.failIncompleteStages(exhausted.stream().map(ContextAiStateRow::contextId).toList());
		exhausted.forEach(AiFailedFinalizer::logFinalized);
		return exhausted;
	}

	/**
	 * 명세 6.3은 "{@code contextId}와 마지막 실패 사유"를 남기라고 하는데, <b>사유는 Spring 쪽에
	 * 존재하지 않는다.</b> 202 이후의 실패는 FastAPI 내부에서 일어나고 통보 경로가 없다. 그래서 우리가
	 * 가진 단서 — 종결 직전의 단계별 status와 소진한 재시도 횟수 — 를 남기고, 사유를 어디서 찾아야
	 * 하는지 문장으로 가리킨다. 이것이 Preset·모델 설정 문제를 판별할 출발점이다.
	 */
	private static void logFinalized(ContextAiStateRow row) {
		log.warn("AI 처리를 FAILED로 종결한다: contextId={}, embedding={}, keyword={}, retryCount={}. "
				+ "재시도를 모두 소진하고 만료됐다 — 실패 사유는 FastAPI 로그에서 확인해야 한다"
				+ "(202 이후의 실패는 Spring에 통보되지 않는다).",
			row.contextId(), row.embeddingStatus(), row.keywordStatus(), row.retryCount());
	}
}
