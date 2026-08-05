package com.pinlog.pinlogback.domain.feed.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pinlog.pinlogback.domain.feed.service.FeedCandidate;

/**
 * 후보 채널 쿼리와 최종 재검증(feed-scoring 2.2, feed-profile-cache 6.2).
 *
 * <p>JPA가 아니라 JDBC를 쓰는 이유가 둘이다. 후보 조회는 엔티티 그래프가 필요 없는 id 스캔이고,
 * Keyword 집계는 FastAPI 소유인 {@code ai} 스키마를 크로스 조인해야 하는데 백엔드는 그 테이블을
 * 엔티티로 매핑하지 않는다({@code core.feed_event}도 같은 이유로 조회만 한다).
 *
 * <p>모든 채널이 같은 노출 조건을 건다 — 활성·발행·소유자 미탈퇴·본인 제외·{@code record_count > 0}.
 * 한 단계만 빠져도 삭제되었거나 비공개인 데이터가 타인에게 노출된다. 이 조건을 자바 코드가 아니라
 * <b>WHERE 절에</b> 두는 것이 규약이다(feed-recommendation 3.3).
 *
 * <p>최신성 기준 시각은 {@code published_at}을 그대로 쓴다. 발행된 행은 그 값을 반드시 가지므로
 * ({@code is_published = true} 필터 + V5 {@code ck_collection_published_at} CHECK, BD-33)
 * {@code COALESCE}로 감쌀 대상이 없다. 감싸면 정렬키가 표현식이 되어 {@code ix_collection_feed}의
 * 순서를 쓸 수 없게 되므로 <b>막는 것 없이 전체 정렬만 유발한다</b> — 실측 근거는 BI-38이고,
 * 회귀는 {@code FeedChannelPlanTests}가 계획으로 잡는다.
 */
@Repository
public class FeedCandidateRepository {

	/**
	 * {@code ix_collection_feed}({@code is_published, published_at DESC})가 이 채널의 전제다.
	 *
	 * <p><b>정렬키를 표현식으로 감싸지 말 것.</b> 인덱스는 {@code published_at} 순서만 저장하므로
	 * {@code ORDER BY}에 함수·연산이 끼면 플래너가 그 순서를 쓸 수 없고, 상위 {@code :limit}건만
	 * 필요한데도 조건에 맞는 행 전부를 정렬한다. 천만 건 실측에서 664,689행 Seq Scan · 241ms 대
	 * 105행 Index Scan · 1.66ms로 145배 차이였다(BI-38).
	 *
	 * <p>가시성이 package-private인 이유는 {@code FeedChannelPlanTests}가 이 상수를 그대로
	 * EXPLAIN해 계획을 검증하기 때문이다. 테스트가 SQL 사본을 들면 본체만 바뀌었을 때 통과한다.
	 */
	static final String RECENT_SQL = """
		SELECT c.id AS collection_id, c.member_id AS owner_id,
			c.published_at AS published_at
		FROM core.collection c
		JOIN core.member m ON m.id = c.member_id
		WHERE c.deleted_at IS NULL
			AND c.is_published = true
			AND c.record_count > 0
			AND c.member_id <> :me
			AND m.deleted_at IS NULL
		ORDER BY c.published_at DESC, c.id DESC
		LIMIT :limit
		""";

	/**
	 * 이 채널은 {@code ix_collection_feed}를 쓰지 않는다 — 그것이 맞다.
	 *
	 * <p>{@code follow}에서 출발해 {@code followee_member_id}로 Collection을 찾으므로 발행 전체를
	 * 훑지 않고, 올바른 인덱스는 {@code ix_collection_member}다. 마지막 정렬은 팔로우한 사람의
	 * Collection 수만큼이라 싸다(천만 건 실측 0.99ms, BI-38).
	 *
	 * <p>정렬키에서 {@code COALESCE}를 뺀 것은 {@link #RECENT_SQL}과 일관성을 위한 정리이며
	 * 이 채널의 성능 때문이 아니다. 값이 바뀌지 않는 근거는 같다 — {@code is_published = true}
	 * 필터 아래에서는 V5 CHECK가 NOT NULL을 보장한다(BD-33).
	 */
	static final String FOLLOWED_SQL = """
		SELECT c.id AS collection_id, c.member_id AS owner_id,
			c.published_at AS published_at
		FROM core.follow f
		JOIN core.collection c ON c.member_id = f.followee_member_id
		JOIN core.member m ON m.id = c.member_id
		WHERE f.follower_member_id = :me
			AND f.deleted_at IS NULL
			AND c.deleted_at IS NULL
			AND c.is_published = true
			AND c.record_count > 0
			AND c.member_id <> :me
			AND m.deleted_at IS NULL
		ORDER BY c.published_at DESC, c.id DESC
		LIMIT :limit
		""";

	private static final String SAMPLE_FROM_PIVOT_SQL = """
		SELECT c.id AS collection_id, c.member_id AS owner_id,
			c.published_at AS published_at
		FROM core.collection c
		JOIN core.member m ON m.id = c.member_id
		WHERE c.id >= :pivot
			AND c.deleted_at IS NULL
			AND c.is_published = true
			AND c.record_count > 0
			AND c.member_id <> :me
			AND m.deleted_at IS NULL
		ORDER BY c.id
		LIMIT :limit
		""";

	private static final String SAMPLE_WRAPPED_SQL = """
		SELECT c.id AS collection_id, c.member_id AS owner_id,
			c.published_at AS published_at
		FROM core.collection c
		JOIN core.member m ON m.id = c.member_id
		WHERE c.id < :pivot
			AND c.deleted_at IS NULL
			AND c.is_published = true
			AND c.record_count > 0
			AND c.member_id <> :me
			AND m.deleted_at IS NULL
		ORDER BY c.id
		LIMIT :limit
		""";

	private static final String VERIFY_SQL = """
		SELECT c.id AS collection_id, c.title AS title, c.record_count AS record_count,
			c.created_at AS created_at
		FROM core.collection c
		JOIN core.member m ON m.id = c.member_id
		WHERE c.id IN (:ids)
			AND c.deleted_at IS NULL
			AND c.is_published = true
			AND c.record_count > 0
			AND m.deleted_at IS NULL
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public FeedCandidateRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 최신 발행 채널 — 신규 Collection이 최소한 노출될 기회. */
	public List<FeedCandidate> findRecent(long memberId, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		return jdbc.query(RECENT_SQL, Map.of("me", memberId, "limit", limit),
			(rs, rowNum) -> map(rs, false, false));
	}

	/** 팔로우 채널 — 이미 관심을 표현한 Shelf. 이 채널 출처가 점수 공식의 {@code followSignal}이다. */
	public List<FeedCandidate> findFollowed(long memberId, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		return jdbc.query(FOLLOWED_SQL, Map.of("me", memberId, "limit", limit),
			(rs, rowNum) -> map(rs, true, false));
	}

	/**
	 * 탐색용 무작위 채널 — 필터 버블 이탈.
	 *
	 * <p>{@code ORDER BY random()}은 전체 스캔이므로 쓰지 않고 <b>id 범위 기반 표본</b>을 뽑는다
	 * (feed-scoring 2.2). 시작점만 정하고 PK 순서로 읽으면 인덱스를 그대로 쓴다.
	 *
	 * <p>{@code seed}는 Feed Session의 {@code requestId}에서 온다. 그래서 같은 Session의 다음
	 * 페이지가 <b>같은 표본</b>을 복원하고, 페이지 간 중복·누락이 생기지 않는다.
	 */
	public List<FeedCandidate> findRandomSample(long memberId, int limit, long seed) {
		if (limit <= 0) {
			return List.of();
		}
		Long maxId = jdbc.queryForObject("SELECT max(id) FROM core.collection", Map.of(), Long.class);
		if (maxId == null || maxId <= 0) {
			return List.of();
		}
		long pivot = Math.floorMod(seed, maxId) + 1;

		List<FeedCandidate> sampled = jdbc.query(SAMPLE_FROM_PIVOT_SQL,
			Map.of("me", memberId, "pivot", pivot, "limit", limit), (rs, rowNum) -> map(rs, false, true));
		if (sampled.size() >= limit) {
			return sampled;
		}
		// 표본 구간이 끝에 걸리면 앞쪽으로 되감아 채운다. 되감지 않으면 pivot이 클수록 후보가
		// 마르고, 그러면 탐색 슬롯이 조용히 비어 탐색 비중 20%가 지켜지지 않는다.
		List<FeedCandidate> wrapped = jdbc.query(SAMPLE_WRAPPED_SQL,
			Map.of("me", memberId, "pivot", pivot, "limit", limit - sampled.size()),
			(rs, rowNum) -> map(rs, false, true));
		return Stream.concat(sampled.stream(), wrapped.stream()).toList();
	}

	/**
	 * 최종 선정분의 Core 상태 재검증과 표시용 데이터 조회를 겸한다(feed-profile-cache 6.2).
	 *
	 * <p>후보 전체(약 200)가 아니라 최종 선정분에만 수행하므로 비용이 작다. 결과에 없는 id는
	 * 응답에서 조용히 제외한다 — 오류로 만들지 않는다.
	 *
	 * @return 입력 순서를 보존한 카드 목록. 재검증에서 탈락한 id는 빠진다
	 */
	public List<FeedCollectionCard> findVerifiedCards(List<Long> collectionIds) {
		if (collectionIds.isEmpty()) {
			return List.of();
		}
		Map<Long, FeedCollectionCard> found = jdbc.query(VERIFY_SQL, Map.of("ids", collectionIds),
				(rs, rowNum) -> new FeedCollectionCard(
					rs.getLong("collection_id"),
					rs.getString("title"),
					rs.getInt("record_count"),
					rs.getTimestamp("created_at").toInstant()))
			.stream()
			.collect(Collectors.toMap(FeedCollectionCard::collectionId, card -> card));
		return collectionIds.stream().map(found::get).filter(Objects::nonNull).toList();
	}

	/** 이벤트 수집에서 실재하지 않는 Collection을 걸러내기 위한 조회(feed-event 4장). */
	public List<Long> findExistingIds(List<Long> collectionIds) {
		if (collectionIds.isEmpty()) {
			return List.of();
		}
		return jdbc.queryForList(
			"SELECT id FROM core.collection WHERE id IN (:ids) AND deleted_at IS NULL",
			Map.of("ids", collectionIds), Long.class);
	}

	private FeedCandidate map(ResultSet rs, boolean fromFollow, boolean fromRandom) throws SQLException {
		return new FeedCandidate(
			rs.getLong("collection_id"),
			rs.getLong("owner_id"),
			rs.getTimestamp("published_at").toInstant(),
			fromFollow,
			fromRandom);
	}
}
