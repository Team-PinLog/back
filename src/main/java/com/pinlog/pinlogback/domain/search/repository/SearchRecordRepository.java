package com.pinlog.pinlogback.domain.search.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * FastAPI가 준 Record id를 <b>Core 기준으로 다시 검증</b>한다(AI 설계 9.5, AI 파트 소유 명세
 * {@code docs/ai/spec/ai-response-assembly.md} 6.1).
 *
 * <p>재검증이 필요한 이유는 셋이다(명세 6.2).
 *
 * <ul>
 *   <li>{@code ai.context_embedding.is_deleted}는 <b>보조 방어선</b>이라 삭제 직후 짧은 창에서 Core와
 *       어긋날 수 있다.</li>
 *   <li>{@code ai.context_embedding.user_id}는 비정규화 값이다. 검색 범위 필터로는 충분해도
 *       <b>인가 판단의 근거로는 부족하다</b> — 인가의 원본은 Core다.</li>
 *   <li>FastAPI는 애초에 User 인증을 판단하지 않는다. {@code userId}를 범위 필터로 신뢰해서 쓸 뿐이다.</li>
 * </ul>
 *
 * <p>그래서 이 조회는 {@code recordId IN (...)}에 소유권·삭제·활성 Context 조건을 <b>함께</b> 건다.
 * 자바 코드에서 걸러 내지 않는 이유는 {@code ContextKeywordRepository}와 같다 — 조건이 코드에 있으면
 * 호출 경로가 늘 때 조용히 누락되고, 그 누락이 곧 남의 기록 노출이다.
 */
@Repository
public class SearchRecordRepository {

	/**
	 * 명세 6.1의 SQL을 그대로 따른다. 네 조건이 각각 재검증 항목 하나에 대응한다.
	 *
	 * <ul>
	 *   <li>{@code r.member_id = :memberId} — 소유권</li>
	 *   <li>{@code r.deleted_at IS NULL} — Record 삭제 여부</li>
	 *   <li>{@code EXISTS (... ct.deleted_at IS NULL)} — 활성 Context 존재. Context를 전부 지운
	 *       Record는 사용자에게 빈 카드로 보이므로 결과에서 뺀다</li>
	 *   <li>{@code JOIN core.place} — Place 존재. INNER JOIN이라 조인 실패가 곧 제외다</li>
	 * </ul>
	 *
	 * <p>JPA가 아니라 SQL로 두는 이유: 이 쿼리의 값은 <b>조건 목록이 명세와 한 줄씩 대응한다</b>는
	 * 데 있다. 파생 쿼리 메서드로 흩으면 그 대응이 이름 속으로 사라진다.
	 */
	private static final String VERIFY_SQL = """
		SELECT r.id AS record_id, r.created_at AS created_at,
			p.id AS place_id, p.name AS place_name, p.address AS place_address,
			p.lat AS lat, p.lng AS lng
		FROM core.record r
		JOIN core.place p ON p.id = r.place_id
		WHERE r.id IN (:recordIds)
			AND r.member_id = :memberId
			AND r.deleted_at IS NULL
			AND EXISTS (
				SELECT 1 FROM core.context ct
				WHERE ct.record_id = r.id AND ct.deleted_at IS NULL
			)
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public SearchRecordRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 통과한 것만 담긴 Map. <b>순서는 담지 않는다</b> — 유사도 순서의 원본은 FastAPI 응답이므로
	 * 호출부가 그 순서로 이 Map을 훑는다(명세 6.3). 여기서 정렬을 흉내내면 정렬 기준이 두 곳이 된다.
	 *
	 * @return Record id → 검증 통과 Record. 탈락한 id는 <b>키가 없다</b>
	 */
	public Map<Long, VerifiedSearchRecord> findVerified(List<Long> recordIds, long memberId) {
		if (recordIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, VerifiedSearchRecord> verified = new LinkedHashMap<>();
		jdbc.query(VERIFY_SQL, Map.of("recordIds", recordIds, "memberId", memberId), rows -> {
			VerifiedSearchRecord record = new VerifiedSearchRecord(
				rows.getLong("record_id"),
				rows.getTimestamp("created_at").toInstant(),
				rows.getLong("place_id"),
				rows.getString("place_name"),
				rows.getString("place_address"),
				rows.getBigDecimal("lat"),
				rows.getBigDecimal("lng"));
			verified.put(record.recordId(), record);
		});
		return verified;
	}
}
