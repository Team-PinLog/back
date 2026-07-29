package com.pinlog.pinlogback.domain.feed.repository;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.pinlog.pinlogback.domain.feed.entity.FeedEventType;

/**
 * {@code core.feed_event} 접근(feed-event 2장·5장). append-only 관측 로그이므로 UPDATE·DELETE가
 * 없고 소프트 삭제도 없다. 테이블은 AI 구간 마이그레이션 {@code V102}가 소유하며 여기서
 * 재정의하지 않는다.
 */
@Repository
public class FeedEventRepository {

	private static final String INSERT_SQL = """
		INSERT INTO core.feed_event (member_id, collection_id, place_id, event, request_id, position)
		VALUES (:memberId, :collectionId, :placeId, :event, :requestId, :position)
		""";

	/**
	 * 노출 패널티 집계(feed-event 5장). {@code COUNT(DISTINCT request_id)}를 쓰는 이유는 클라이언트
	 * 재시도로 생긴 같은 Session의 중복 IMPRESSION을 1회로 흡수하기 위해서다 — DB 유니크 제약으로
	 * 막지 않는 대신 집계에서 흡수한다.
	 *
	 * <p>후보 id 목록으로 범위를 좁혀 <b>한 번만</b> 조회한다. {@code ix_feed_event_penalty}에
	 * 전적으로 의존하는 쿼리다.
	 */
	private static final String IMPRESSION_COUNT_SQL = """
		SELECT collection_id AS collection_id, count(DISTINCT request_id) AS impressions
		FROM core.feed_event
		WHERE member_id = :me
			AND event = 'IMPRESSION'
			AND collection_id IN (:ids)
			AND created_at > now() - make_interval(secs => :windowSeconds)
		GROUP BY collection_id
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public FeedEventRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Map<Long, Integer> countRecentImpressions(long memberId, List<Long> collectionIds,
		Duration window) {
		if (collectionIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> params = new HashMap<>();
		params.put("me", memberId);
		params.put("ids", collectionIds);
		params.put("windowSeconds", window.toSeconds());

		Map<Long, Integer> counts = new HashMap<>();
		jdbc.query(IMPRESSION_COUNT_SQL, params,
				(rs, rowNum) -> Map.entry(rs.getLong("collection_id"), rs.getInt("impressions")))
			.forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
		return Map.copyOf(counts);
	}

	/**
	 * 이벤트를 한 번의 batch INSERT로 기록한다(feed-event 3.1). 건별 INSERT를 돌면 응답 20건마다
	 * 왕복이 20번 생긴다.
	 */
	public void insertAll(List<FeedEventRow> rows) {
		if (rows.isEmpty()) {
			return;
		}
		SqlParameterSource[] batch = rows.stream().map(FeedEventRepository::toParams)
			.toArray(SqlParameterSource[]::new);
		jdbc.batchUpdate(INSERT_SQL, batch);
	}

	private static SqlParameterSource toParams(FeedEventRow row) {
		return new MapSqlParameterSource()
			.addValue("memberId", row.memberId())
			.addValue("collectionId", row.collectionId())
			.addValue("placeId", row.placeId())
			.addValue("event", row.event().name())
			.addValue("requestId", row.requestId())
			.addValue("position", row.position());
	}

	/**
	 * 한 행. {@code member_id}는 항상 요청자 본인이며 <b>요청 본문에서 받지 않는다</b> —
	 * 본문으로 받으면 타인 이벤트를 위조할 수 있다(feed-event 4장).
	 *
	 * @param memberId 이벤트를 발생시킨 요청자(인증 컨텍스트에서 결정된다)
	 * @param collectionId 대상 Collection
	 * @param placeId CLICK·SAVE가 특정 Place를 향한 경우에만. IMPRESSION은 항상 null
	 * @param event 이벤트 종류
	 * @param requestId Feed Session 식별자
	 * @param position 응답 목록에서의 0-based 순서
	 */
	public record FeedEventRow(
		long memberId,
		long collectionId,
		Long placeId,
		FeedEventType event,
		UUID requestId,
		Integer position
	) {
	}
}
