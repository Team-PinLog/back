package com.pinlog.pinlogback.domain.record.repository;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Context가 소프트 삭제될 때 {@code ai} 스키마의 파생 데이터에 무효화 표시를 남긴다
 * (데이터모델 6.4~6.6·6.9, API 명세 3.4 "AI 파생 데이터"). <b>물리 삭제가 아니라 표시다.</b>
 *
 * <p>{@code ai} 스키마의 소유는 AI 파트지만 이 두 컬럼만은 백엔드가 쓴다(데이터모델 1.3 쓰기
 * 매트릭스). 같은 PostgreSQL 인스턴스이므로 core 삭제와 <b>단일 트랜잭션</b>으로 묶이고, 그것이
 * 이 쓰기를 백엔드에 둔 이유다 — 별도 트랜잭션·비동기로 분리하면 "core는 지워졌는데 파생
 * 데이터는 살아 있는" 부분 실패가 남는다.
 *
 * <p>백엔드가 건드리는 것은 {@code context_ai_state}의 두 status와 {@code context_embedding.is_deleted}
 * 뿐이다. {@code embedding}·{@code embedding_profile}과 {@code PROCESSING}·{@code COMPLETED} 전이는
 * AI 워커 전용이고, {@code ai.context_keyword}는 조회가 {@code context_ai_state}를 조인해 자동
 * 제외하므로 백엔드가 쓰지 않는다.
 *
 * <p><b>배치는 스키마 소유가 아니라 소비 도메인을 따른다.</b> {@code ai.keyword_preset}을 읽는
 * {@code FeedKeywordRepository}가 {@code domain/feed} 아래 있는 것과 같은 선례이고,
 * {@code docs/development/package-structure.md}도 {@code ai} 도메인을 만들지 않는 방향을 명시한다.
 * 호출부 둘({@code RecordDeletionService}·{@code RecordService})이 모두 {@code domain/record/service}다.
 */
@Repository
public class AiDerivedDataRepository {

	/**
	 * 무효화 status. <b>값 집합의 소유는 AI 파트다</b>({@code V100__ai_tables.sql}의 CHECK 제약).
	 * SQL 두 개와 테스트가 각자 문자열을 들면 AI 쪽이 어휘를 바꿀 때 고쳐야 할 자리가 흩어지므로,
	 * 결합 표면을 이 상수 하나로 모은다.
	 */
	public static final String CANCELLED = "CANCELLED";

	/**
	 * 조건 없는 전이다 — {@code COMPLETED}·{@code FAILED}도 덮는다. {@code ai.context_keyword}에는
	 * {@code is_deleted}에 해당하는 컬럼이 없어 키워드 조회 제외를 {@code keyword_status}가 단독으로
	 * 담당하기 때문이다. {@code COMPLETED}를 남기면 임베딩 검색에서는 걸러지지만 삭제·탈퇴한
	 * 사용자의 Keyword가 키워드 조회에 계속 노출된다(AI 설계 §11.1).
	 *
	 * <p>두 status를 모두 바꾼다. embedding과 keyword는 독립 전이하는 별개 축이다.
	 */
	private static final String CANCEL_STATE_SQL = """
		UPDATE ai.context_ai_state
		SET embedding_status = '%s',
			keyword_status   = '%s',
			updated_at       = now()
		WHERE context_id IN (:contextIds)
		""".formatted(CANCELLED, CANCELLED);

	/**
	 * 검색 제외는 {@code context_embedding.is_deleted = false} 필터가 단독으로 담당한다(MVP는 정확
	 * cosine 검색이라 ANN 인덱스가 없다). 이 표시가 없으면 삭제한 Context가 검색 결과에 계속 나온다.
	 */
	private static final String MARK_EMBEDDING_DELETED_SQL = """
		UPDATE ai.context_embedding
		SET is_deleted = true,
			updated_at = now()
		WHERE context_id IN (:contextIds)
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public AiDerivedDataRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 넘긴 Context들의 파생 데이터를 무효화한다. <b>호출자의 삭제 트랜잭션 안에서 부른다.</b>
	 *
	 * <p>영향 행이 0이어도 오류가 아니다 — State·Embedding이 생기기 전에 삭제된 Context가 정상
	 * 경로에 있다(비동기 생성이라 커밋 직후에는 아직 없다). 늦게 도착하는 INSERT·UPDATE는 AI
	 * 워커 쪽 {@code WHERE status = 'PROCESSING'} 가드가 막는다.
	 *
	 * @param contextIds 무효화할 Context id. <b>{@code null}을 받지 않는다</b> — 대상이 없으면 빈
	 *     목록을 넘긴다. "지울 것이 없다"와 "목록을 못 구했다"를 호출부가 구분하지 않고 넘기면
	 *     조용히 무효화가 빠지므로, 이 계약을 {@code @Nullable}로 완화하지 않는다.
	 */
	public void invalidate(@NonNull List<Long> contextIds) {
		if (contextIds.isEmpty()) {
			return;
		}
		Map<String, Object> parameters = Map.of("contextIds", contextIds);
		jdbc.update(CANCEL_STATE_SQL, parameters);
		jdbc.update(MARK_EMBEDDING_DELETED_SQL, parameters);
	}

	/** 단건 편의 오버로드. 계약상 동작은 {@link #invalidate(List)}와 같다. */
	public void invalidate(@NonNull Long contextId) {
		invalidate(List.of(contextId));
	}
}
