package com.pinlog.pinlogback.domain.feed.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * <p><b>집계는 {@code code}로 키를 잡고, 응답에 실을 {@code display_name}은 따로 조회한다.</b>
 * {@code code}는 불변 식별자이고 {@code display_name}은 언제든 바뀔 수 있는 표시용 라벨이다 —
 * 표시값을 점수 계산의 키로 쓰면 라벨을 고친 순간 그 이전에 계산된 Profile과 매칭이 어긋난다.
 * 양쪽 키가 같기만 하면 Jaccard 자체는 성립하므로 오류도 나지 않고 테스트도 깨지지 않는다.
 * 추천 품질만 조용히 나빠진다(S15P11A705-252, back#146).
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

	/**
	 * 응답에 실을 표시값. 가시성 화이트리스트를 특징 집계와 <b>똑같이</b> 둔다 — 어느 한쪽이
	 * 무너져도 감춰야 할 라벨이 응답에 실리지 않는 이중 방어다.
	 *
	 * <p>{@code code}에 UNIQUE 제약이 있으므로(V100) 결과는 code당 한 행이다.
	 */
	private static final String DISPLAY_NAMES_SQL = """
		SELECT kp.code AS code, kp.display_name AS display_name
		FROM ai.keyword_preset kp
		WHERE kp.code IN (:codes)
			AND kp.visibility = 'PUBLIC'
			AND kp.is_active = true
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
	 * 응답 조립 직전에 {@code code}를 화면 표시값으로 옮긴다(API 명세 08 §6.1 — {@code code}는
	 * 내부 식별용이라 노출하지 않는다).
	 *
	 * <p><b>페이지 전체의 code를 모아 한 번에 조회한다.</b> 항목마다 부르면 그대로 N+1이고,
	 * 프리셋이 27개뿐이라 개발 데이터에서는 증상이 드러나지 않는다 — 쿼리 수로 고정해 둔다
	 * (feed-tests N8).
	 *
	 * @return Keyword {@code code} → {@code display_name}. <b>노출 대상이 아니거나 폐기된 Preset은
	 *     키가 없다</b> — 호출부는 그런 code를 응답에서 빼야 한다. {@code code}로 대신 채우면 그
	 *     폴백이 곧 명세 위반이다
	 */
	public Map<String, String> findPublicDisplayNames(Collection<String> codes) {
		if (codes.isEmpty()) {
			return Map.of();
		}
		Map<String, String> byCode = new LinkedHashMap<>();
		jdbc.query(DISPLAY_NAMES_SQL, Map.of("codes", Set.copyOf(codes)), rows -> {
			byCode.put(rows.getString("code"), rows.getString("display_name"));
		});
		return Map.copyOf(byCode);
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
