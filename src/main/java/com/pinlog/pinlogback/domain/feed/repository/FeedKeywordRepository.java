package com.pinlog.pinlogback.domain.feed.repository;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pinlog.pinlogback.domain.feed.service.FeedProfile;

/**
 * Keyword 분포 집계(feed-recommendation 3.1·3.3). {@code ai} 스키마를 읽기 조인만 한다 —
 * 요청 경로에서 FastAPI·Embedding·LLM을 호출하지 않는다.
 *
 * <p><b>가시성 필터를 WHERE 절에 두는 것이 이 클래스의 존재 이유다.</b> 자바 코드에서 거르면
 * 한 경로만 빠뜨려도 감춰야 할 Keyword가 새어 나간다.
 *
 * <table border="1">
 *   <caption>가시성별 취급</caption>
 *   <tr><th>visibility</th><th>본인 Profile</th><th>타인 Collection 특징·응답</th></tr>
 *   <tr><td>{@code PUBLIC}</td><td>쓴다</td><td>쓴다</td></tr>
 *   <tr><td>{@code PRIVATE_ONLY}</td><td>쓴다</td><td><b>쓰지 않는다</b></td></tr>
 *   <tr><td>{@code BLOCKED}</td><td>쓰지 않는다</td><td>쓰지 않는다</td></tr>
 * </table>
 *
 * <p>이 비대칭이 의도된 설계다(feed-scoring 3.2). {@code PRIVATE_ONLY}가 Collection 쪽에
 * 들어가면 타인에게 감춰야 할 정보가 추천 결과를 통해 드러난다.
 */
@Repository
public class FeedKeywordRepository {

	private static final String COLLECTION_KEYWORDS_SQL = """
		SELECT cr.collection_id AS collection_id, kp.code AS code, count(*) AS weight
		FROM core.collection_record cr
		JOIN core.record r ON r.id = cr.record_id AND r.deleted_at IS NULL
		JOIN core.context ctx ON ctx.record_id = r.id AND ctx.deleted_at IS NULL
		JOIN ai.context_keyword ck ON ck.context_id = ctx.id
		JOIN ai.keyword_preset kp ON kp.id = ck.keyword_id
		WHERE cr.collection_id IN (:ids)
			AND cr.deleted_at IS NULL
			AND kp.visibility = 'PUBLIC'
			AND kp.is_active = true
		GROUP BY cr.collection_id, kp.code
		""";

	private static final String PROFILE_KEYWORDS_SQL = """
		SELECT kp.code AS code, count(*) AS weight
		FROM core.record r
		JOIN core.context ctx ON ctx.record_id = r.id AND ctx.deleted_at IS NULL
		JOIN ai.context_keyword ck ON ck.context_id = ctx.id
		JOIN ai.keyword_preset kp ON kp.id = ck.keyword_id
		WHERE r.member_id = :me
			AND r.deleted_at IS NULL
			AND kp.visibility IN ('PUBLIC', 'PRIVATE_ONLY')
			AND kp.is_active = true
		GROUP BY kp.code
		""";

	private static final String ACTIVE_RECORD_COUNT_SQL =
		"SELECT count(*) FROM core.record WHERE member_id = :me AND deleted_at IS NULL";

	private final NamedParameterJdbcTemplate jdbc;

	public FeedKeywordRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 후보 전체의 {@code PUBLIC} Keyword 분포를 <b>한 번의 쿼리로</b> 집계한다(feed-tests N4·N5).
	 * Collection별 반복 조회는 후보 200건에 대해 그대로 N+1이 된다.
	 *
	 * @return Collection id → (Keyword code → 정규화 가중치). Keyword가 없는 Collection은 키가 없다
	 */
	public Map<Long, Map<String, Double>> findPublicKeywordWeights(List<Long> collectionIds) {
		if (collectionIds.isEmpty()) {
			return Map.of();
		}
		List<KeywordCount> rows = jdbc.query(COLLECTION_KEYWORDS_SQL, Map.of("ids", collectionIds),
			(rs, rowNum) -> new KeywordCount(
				rs.getLong("collection_id"), rs.getString("code"), rs.getDouble("weight")));

		Map<Long, Map<String, Double>> raw = new HashMap<>();
		for (KeywordCount row : rows) {
			raw.computeIfAbsent(row.collectionId(), key -> new LinkedHashMap<>())
				.put(row.code(), row.weight());
		}
		Map<Long, Map<String, Double>> normalized = new HashMap<>(raw.size());
		raw.forEach((collectionId, weights) -> normalized.put(collectionId, normalize(weights)));
		return Map.copyOf(normalized);
	}

	/**
	 * 요청자의 관심 Profile. {@code PRIVATE_ONLY}가 여기에만 들어간다.
	 *
	 * <p>Record 수는 Keyword와 별개로 센다 — Keyword가 하나도 없어도 "Record는 있다"를 구분해야
	 * Cold Start 판정이 정확해진다(feed-scoring 5.1).
	 */
	public FeedProfile findProfile(long memberId) {
		List<KeywordCount> rows = jdbc.query(PROFILE_KEYWORDS_SQL, Map.of("me", memberId),
			(rs, rowNum) -> new KeywordCount(0L, rs.getString("code"), rs.getDouble("weight")));

		Map<String, Double> weights = new LinkedHashMap<>();
		rows.forEach(row -> weights.put(row.code(), row.weight()));
		Integer recordCount = jdbc.queryForObject(
			ACTIVE_RECORD_COUNT_SQL, Map.of("me", memberId), Integer.class);
		return new FeedProfile(normalize(weights), recordCount == null ? 0 : recordCount);
	}

	/**
	 * 합이 1이 되도록 정규화한다. 정규화하지 않으면 Keyword가 많은 Collection의 절대 빈도가
	 * weighted Jaccard의 분모를 키워 크기 편향이 되살아난다(feed-scoring 3.2).
	 */
	private Map<String, Double> normalize(Map<String, Double> weights) {
		double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
		if (total == 0.0) {
			return Map.of();
		}
		Map<String, Double> normalized = new LinkedHashMap<>(weights.size());
		weights.forEach((code, weight) -> normalized.put(code, weight / total));
		return Map.copyOf(normalized);
	}

	private record KeywordCount(long collectionId, String code, double weight) {
	}
}
