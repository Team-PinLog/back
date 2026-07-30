package com.pinlog.pinlogback.domain.ai.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.ai.repository.AiRescanStateRepository;
import com.pinlog.pinlogback.domain.ai.repository.ContextAiStateRow;

/**
 * 재스캔 후보를 잠그고 재시도 예산을 소진시킨다
 * (AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 4장·5장).
 *
 * <p><b>이 클래스의 트랜잭션 경계가 명세 3.1의 커밋 경계다.</b> 후보 잠금과 {@code retry_count} 증가는
 * 한 트랜잭션에서 끝내고, FastAPI 호출은 이 메서드가 반환한 뒤 <b>트랜잭션 밖에서</b> 한다. 외부 호출을
 * 안에 두면 호출 지연만큼 행 잠금과 DB 커넥션이 붙잡혀 있게 된다.
 *
 * <p>같은 이유로 호출자와 별 Bean이다. {@code @Transactional}은 프록시로 걸리므로 스케줄러가 자기
 * 메서드를 부르면 트랜잭션이 열리지 않고, 그러면 {@code FOR UPDATE SKIP LOCKED}의 잠금이 조회 직후
 * 풀려 중복 방어가 사라진다.
 */
@Service
public class AiRescanCandidateService {

	private final AiRescanStateRepository repository;
	private final AiRescanProperties properties;

	public AiRescanCandidateService(AiRescanStateRepository repository, AiRescanProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	/**
	 * 만료된 미완료 작업을 잠그고 {@code retry_count}를 올린다.
	 *
	 * <p><b>반환 시점에 증가는 커밋된다.</b> 그래야 뒤이은 외부 호출이 실패하더라도 예산이 실제로
	 * 줄어들고, 같은 행이 다음 회차에 다시 잡혀 무한 재시도가 되지 않는다.
	 *
	 * @return 잠근 행들의 <b>증가 전</b> 상태. 만료된 단계가 무엇이었는지가 이 값에만 남는다
	 */
	@Transactional
	public List<ContextAiStateRow> claimStale() {
		List<ContextAiStateRow> stale = repository.lockStale(
			properties.pendingExpiry(), properties.processingExpiry(),
			properties.maxRetry(), properties.batchSize());
		repository.incrementRetryCount(stale.stream().map(ContextAiStateRow::contextId).toList());
		return stale;
	}

	/**
	 * 지금의 상태 행. 삭제된 Context를 만났을 때 그 상태가 {@code CANCELLED}인지 확인하는 데만 쓴다
	 * (명세 5.1). 후보 조회는 {@code CANCELLED}를 잡지 않으므로 {@link #claimStale()}이 준 값으로는
	 * 판정할 수 없다 — 삭제는 그 뒤에 일어났을 수 있다.
	 */
	@Transactional(readOnly = true)
	public Optional<ContextAiStateRow> findCurrentState(long contextId) {
		return repository.findOne(contextId);
	}
}
