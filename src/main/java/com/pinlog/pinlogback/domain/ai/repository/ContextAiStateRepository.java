package com.pinlog.pinlogback.domain.ai.repository;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Context가 생성될 때 {@code ai.context_ai_state}에 처리 대기 행을 만든다
 * (AI 파트 소유 명세 {@code docs/ai/spec/context-state-sync.md} 3장).
 *
 * <p>이 행이 없으면 AI 워커는 그 Context가 존재한다는 사실 자체를 모른다. FastAPI는 요청을 받아도
 * 상태 행을 찾지 못해 아무 것도 하지 않고, 재스캔도 후보로 잡지 못한다 — 임베딩·키워드가 영원히
 * 만들어지지 않는다. <b>그래서 이 INSERT는 Core 저장과 같은 트랜잭션에 들어간다.</b> 별도
 * 트랜잭션으로 떼면 "Core는 저장됐는데 AI State가 없어 영원히 처리되지 않는 Context"가 생긴다.
 *
 * <p>백엔드가 이 테이블에 쓰는 것은 {@code PENDING} 최초 생성과 {@code CANCELLED} 전이뿐이다
 * (명세 8장 쓰기 책임표). {@code PROCESSING}·{@code COMPLETED}와 작업 중 오류 {@code FAILED},
 * {@code embedding}·{@code embedding_profile}은 AI 워커 전용이므로 여기서 건드리지 않는다.
 *
 * <p><b>삭제 경로의 {@code CANCELLED} 전이는 {@code AiDerivedDataRepository}(back#80)가 담당한다.</b>
 * 같은 테이블을 쓰는 클래스를 둘로 나눈 이유는 BD-36에 적었다 — 두 PR이 동시에 열려 있어 한 파일을
 * 공유하면 병합 충돌이 되고, 생성과 삭제는 호출 시점·트랜잭션 경계가 서로 다르다. back#80이 먼저
 * 병합된 뒤 하나로 합칠지는 그때 판단한다.
 */
@Repository
public class ContextAiStateRepository {

	/**
	 * 상태·재시도 예산 기본값은 DB DEFAULT에 맡기지 않고 명시한다. 재시도 예산은 {@code context_id}
	 * 단위이며 새 Context는 언제나 3회의 기회를 새로 갖는다(명세 3.2).
	 *
	 * <p>{@code ON CONFLICT DO NOTHING}인 이유: {@code context_id}가 PK이므로 재호출이 예외가 아니라
	 * 무동작이 되어야 한다. 상태를 되돌리는 전이는 존재하지 않으므로(명세 5.3) 기존 행을 덮어쓰지
	 * 않는다 — {@code DO UPDATE}로 쓰면 이미 {@code COMPLETED}·{@code CANCELLED}인 행을
	 * {@code PENDING}으로 되살리는 통로가 열린다.
	 */
	private static final String INSERT_PENDING_SQL = """
		INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status, retry_count, updated_at)
		VALUES (:contextId, 'PENDING', 'PENDING', 0, now())
		ON CONFLICT (context_id) DO NOTHING
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public ContextAiStateRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 처리 대기 행을 만든다. <b>호출자의 Core 트랜잭션 안에서 부른다.</b>
	 *
	 * <p>{@code core}와 {@code ai}는 같은 PostgreSQL 인스턴스의 서로 다른 스키마라 단일 로컬
	 * 트랜잭션으로 묶인다. 분산 트랜잭션도 Outbox도 필요 없다(명세 2장).
	 */
	public void initializePending(Long contextId) {
		jdbc.update(INSERT_PENDING_SQL, Map.of("contextId", contextId));
	}
}
