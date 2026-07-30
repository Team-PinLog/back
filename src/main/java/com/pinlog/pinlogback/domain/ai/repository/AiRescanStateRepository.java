package com.pinlog.pinlogback.domain.ai.repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 유실·정지된 AI 처리를 찾아내는 조회와 그 뒤처리 UPDATE
 * (AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 4·5·6장).
 *
 * <p><b>후보 조회는 반드시 호출자의 트랜잭션 안에서 부른다.</b> {@code FOR UPDATE SKIP LOCKED}가
 * 잡는 잠금은 트랜잭션이 끝날 때 풀리므로, 트랜잭션 없이 부르면(auto-commit) 조회가 끝나는 즉시
 * 잠금이 사라져 <b>같은 행을 두 인스턴스가 동시에 집는다.</b> 그러면 이 클래스가 존재하는 이유가
 * 없어진다.
 *
 * <p>같은 테이블을 쓰는 {@link ContextAiStateRepository}(생성)·{@link AiDerivedDataRepository}
 * (삭제 무효화)와 파일을 나눈 기준은 <b>호출 시점과 트랜잭션 경계</b>다(BD-36과 같은 기준). 저 둘은
 * 사용자 요청 트랜잭션에 얹혀 돌고, 이쪽은 스케줄러가 자기 트랜잭션을 열어 부른다.
 */
@Repository
public class AiRescanStateRepository {

	/**
	 * 만료 판정. <b>재스캔(4.1)과 Finalizer(6.1)가 이 술어를 공유한다</b> — 명세 6.1이 "만료 기준은
	 * 재스캔과 동일하다"고 정하므로, 두 SQL에 따로 적으면 한쪽만 고쳐 두 경로의 기준이 갈라진다.
	 *
	 * <p>기준 컬럼은 {@code updated_at}이다(명세 4.1). 시각 비교를 DB {@code now()}로 하는 이유는
	 * 애플리케이션과 DB의 시계가 어긋나도 판정이 흔들리지 않게 하기 위해서다 — 인스턴스가 여러 대면
	 * 각자의 시계로 자른 만료 시점이 서로 달라진다.
	 *
	 * <p>초를 {@code double}로 넘기는 이유: {@code make_interval(secs ...)}의 파라미터 타입이
	 * {@code double precision}이라 값도 그 타입으로 보내면 함수 해석에 추론이 끼어들지 않는다.
	 */
	private static final String EXPIRED_STAGE_PREDICATE = """
		(
			(embedding_status = 'PENDING' AND updated_at < now() - make_interval(secs => :pendingSecs))
			OR (keyword_status = 'PENDING' AND updated_at < now() - make_interval(secs => :pendingSecs))
			OR (embedding_status = 'PROCESSING' AND updated_at < now() - make_interval(secs => :procSecs))
			OR (keyword_status = 'PROCESSING' AND updated_at < now() - make_interval(secs => :procSecs))
		)""";

	/**
	 * 재스캔 후보(명세 4.2).
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED}가 하는 일은 두 가지다. 잠금은 다중 인스턴스·실행 겹침에서 같은
	 * Context를 두 번 집는 것을 막고, {@code SKIP LOCKED}는 잠긴 행을 <b>기다리지 않고</b> 건너뛴다 —
	 * 기다리면 배치 전체가 느린 한 행에 묶인다. 이것이 중복 방어의 유일한 장치는 아니며, FastAPI의
	 * {@code PROCESSING} 조건부 UPDATE가 최종 방어선이다(명세 4.2).
	 *
	 * <p>{@code ORDER BY updated_at}으로 가장 오래 멈춘 것부터 처리한다.
	 *
	 * <p><b>FAILED·CANCELLED를 조건에 적지 않는다.</b> 만료 술어가 {@code PENDING}·{@code PROCESSING}
	 * 화이트리스트이므로 자동으로 빠진다. 블랙리스트({@code <> 'COMPLETED'} 등)로 쓰면 나중에 추가되는
	 * status가 조용히 후보가 된다.
	 */
	private static final String LOCK_STALE_SQL = """
		SELECT context_id, embedding_status, keyword_status, retry_count
		FROM ai.context_ai_state
		WHERE retry_count < :maxRetry
			AND %s
		ORDER BY updated_at
		LIMIT :batchSize
		FOR UPDATE SKIP LOCKED
		""".formatted(EXPIRED_STAGE_PREDICATE);

	/**
	 * Finalizer 후보(명세 6.2). 재스캔과 {@code retry_count} 비교 방향만 다르다.
	 *
	 * <p><b>만료 조건이 여기에도 붙는 것이 핵심이다.</b> 없으면 {@code retry_count}를 3으로 올린 그
	 * 회차에서 곧바로 종결되어, 마지막 재시도 요청이 처리될 시간을 갖지 못한다(명세 6.1).
	 */
	private static final String LOCK_EXHAUSTED_SQL = """
		SELECT context_id, embedding_status, keyword_status, retry_count
		FROM ai.context_ai_state
		WHERE retry_count >= :maxRetry
			AND %s
		ORDER BY updated_at
		LIMIT :batchSize
		FOR UPDATE SKIP LOCKED
		""".formatted(EXPIRED_STAGE_PREDICATE);

	/**
	 * 재시도 소진(명세 5장). status는 손대지 않는다 — 만료된 {@code PROCESSING}을 {@code PENDING}으로
	 * 되돌리지 않는다. Spring은 {@code PROCESSING}을 쓰지도, 해제하지도 않으며, FastAPI의 선점 UPDATE가
	 * {@code PROCESSING}을 허용 조건에 포함하므로 재요청만으로 재개된다. Spring이 상태를 손대면 두
	 * 주체가 같은 컬럼을 경쟁적으로 쓰게 되어 소유권 경계가 무너진다.
	 */
	private static final String INCREMENT_RETRY_SQL = """
		UPDATE ai.context_ai_state
		SET retry_count = retry_count + 1,
			updated_at = now()
		WHERE context_id IN (:contextIds)
		""";

	/**
	 * 미완료 단계 종결(명세 6.2).
	 *
	 * <p><b>{@code IN ('PENDING','PROCESSING')} 화이트리스트를 블랙리스트로 바꾸지 않는다.</b> 후보를
	 * 잡은 뒤 UPDATE 직전에 Context가 삭제·교체될 수 있고, 그때 블랙리스트({@code <> 'COMPLETED'})면
	 * {@code CANCELLED}가 {@code FAILED}로 뒤집힌다. 삭제 표시가 실패로 바뀌면 "사용자가 지웠다"와
	 * "AI가 실패했다"를 구별할 수 없게 된다(명세 6.3).
	 *
	 * <p>이미 {@code COMPLETED}인 단계도 그대로 둔다. 부분 성공을 지우면 Embedding을 다시 만들어야
	 * 하므로 부분 재사용의 이점이 사라진다.
	 */
	private static final String FINALIZE_SQL = """
		UPDATE ai.context_ai_state
		SET embedding_status =
				CASE WHEN embedding_status IN ('PENDING','PROCESSING') THEN 'FAILED' ELSE embedding_status END,
			keyword_status =
				CASE WHEN keyword_status IN ('PENDING','PROCESSING') THEN 'FAILED' ELSE keyword_status END,
			updated_at = now()
		WHERE context_id IN (:contextIds)
		""";

	private static final String SELECT_ONE_SQL = """
		SELECT context_id, embedding_status, keyword_status, retry_count
		FROM ai.context_ai_state
		WHERE context_id = :contextId
		""";

	private static final RowMapper<ContextAiStateRow> ROW_MAPPER = (rows, index) -> new ContextAiStateRow(
		rows.getLong("context_id"),
		rows.getString("embedding_status"),
		rows.getString("keyword_status"),
		rows.getInt("retry_count"));

	private final NamedParameterJdbcTemplate jdbc;

	public AiRescanStateRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 재스캔 후보를 잠근다. <b>호출자의 트랜잭션 안에서 부른다.</b> */
	public List<ContextAiStateRow> lockStale(Duration pendingExpiry, Duration processingExpiry,
		int maxRetry, int batchSize) {
		return jdbc.query(LOCK_STALE_SQL,
			candidateParameters(pendingExpiry, processingExpiry, maxRetry, batchSize), ROW_MAPPER);
	}

	/** 재시도를 소진한 만료 후보를 잠근다. <b>호출자의 트랜잭션 안에서 부른다.</b> */
	public List<ContextAiStateRow> lockRetryExhausted(Duration pendingExpiry, Duration processingExpiry,
		int maxRetry, int batchSize) {
		return jdbc.query(LOCK_EXHAUSTED_SQL,
			candidateParameters(pendingExpiry, processingExpiry, maxRetry, batchSize), ROW_MAPPER);
	}

	/** {@code retry_count}를 1 올린다. 상한은 DB {@code CHECK} 제약이 지킨다. */
	public void incrementRetryCount(List<Long> contextIds) {
		if (contextIds.isEmpty()) {
			return;
		}
		jdbc.update(INCREMENT_RETRY_SQL, Map.of("contextIds", contextIds));
	}

	/** 미완료 단계만 {@code FAILED}로 바꾼다. */
	public void failIncompleteStages(List<Long> contextIds) {
		if (contextIds.isEmpty()) {
			return;
		}
		jdbc.update(FINALIZE_SQL, Map.of("contextIds", contextIds));
	}

	/**
	 * 단건 조회. 삭제된 Context의 상태가 {@code CANCELLED}인지 확인하는 정합성 검사에만 쓴다(명세 5.1)
	 * — 후보 조회는 {@code CANCELLED}를 애초에 잡지 않으므로 그 시점의 값을 다시 읽어야 한다.
	 */
	public Optional<ContextAiStateRow> findOne(long contextId) {
		return jdbc.query(SELECT_ONE_SQL, Map.of("contextId", contextId), ROW_MAPPER).stream().findFirst();
	}

	private static Map<String, Object> candidateParameters(Duration pendingExpiry, Duration processingExpiry,
		int maxRetry, int batchSize) {
		return Map.of(
			"pendingSecs", seconds(pendingExpiry),
			"procSecs", seconds(processingExpiry),
			"maxRetry", maxRetry,
			"batchSize", batchSize);
	}

	private static double seconds(Duration expiry) {
		return expiry.toMillis() / 1000.0;
	}
}
