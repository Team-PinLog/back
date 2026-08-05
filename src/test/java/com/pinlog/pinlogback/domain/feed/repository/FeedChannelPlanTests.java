package com.pinlog.pinlogback.domain.feed.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 후보 채널 쿼리가 {@code ix_collection_feed}의 정렬 순서를 쓸 수 있는지 본다(S15P11A705-303).
 *
 * <p><b>왜 실행 시간이 아니라 계획을 보는가.</b> Testcontainers DB는 행이 적어 Seq Scan이 실제로
 * 더 싸다. 시간으로 판정하면 개선 전후가 갈리지 않는다. 그래서 {@code enable_seqscan = off}로
 * 인덱스 경로를 후보에 올린 뒤, 플래너가 <b>인덱스의 순서를 정렬에 쓸 수 있는지</b>를 본다.
 * 이 판정은 표 크기와 무관하다.
 *
 * <p>판별 지점은 {@code Presorted Key}다. 인덱스가 이미 {@code published_at DESC}로 줄 세워
 * 두었으면 플래너는 Incremental Sort로 남은 tiebreaker만 처리하며 이 줄을 남긴다. 정렬키를
 * 표현식으로 감싸면 인덱스 순서를 쓸 수 없어 조건에 맞는 행 전부를 Sort로 넘긴다 —
 * 그때는 {@code Sort Key}에 그 표현식이 그대로 찍히고 {@code Presorted Key}가 없다.
 *
 * <p><b>대안 경로를 전부 막아야 한다</b>({@link #PLANNER_OFF}). 스캔만 막으면 플래너가 조인 방식을
 * 바꿔 다른 인덱스로 새어 나가고, 그러면 정렬키가 올바른데도 판정이 뒤집힌다. 실측 근거는 그
 * 상수의 주석에 있다.
 *
 * <p><b>SQL 사본을 두지 않는다.</b> 리포지터리의 상수를 직접 EXPLAIN한다. 테스트가 사본을 들면
 * 본체만 바뀌었을 때 통과해 거짓 안심을 준다. 그래서 {@code RECENT_SQL}·{@code FOLLOWED_SQL}이
 * package-private이며, 이 테스트가 그 가시성의 유일한 이유다.
 */
@SpringBootTest
class FeedChannelPlanTests extends IntegrationContainerSupport {

	private static final String PRESORTED = "Presorted Key";

	/**
	 * 인덱스 순서를 쓸 수 있는 경로 하나만 남기고 대안을 전부 막는다.
	 *
	 * <p>스캔만 막아서는 부족했다. 실측으로 collection 20~100행 구간에서 플래너가
	 * {@code ix_collection_member}로 <b>Merge Join</b>을 골랐고, 그러면 {@code ix_collection_feed}를
	 * 타지 않아 정렬 순서가 없어 전체 Sort가 붙는다 — 정렬키가 올바른데도 {@code Presorted Key}가
	 * 사라져 <b>거짓 실패</b>가 났다. 행이 더 적거나 더 많으면 통과했으므로, 공유 테스트 DB의
	 * 적재량에 따라 붙었다 떨어졌다 하는 플레이크였다.
	 *
	 * <p>조인 방식까지 고정하면 0행부터 1만 행까지 전 구간에서 {@code ix_collection_feed}를 타고,
	 * 표현식 정렬키일 때는 여전히 {@code Presorted Key}가 없다 — 판별력은 유지된다.
	 */
	private static final List<String> PLANNER_OFF = List.of(
		"enable_seqscan", "enable_bitmapscan", "enable_mergejoin", "enable_hashjoin");

	@Autowired
	private NamedParameterJdbcTemplate jdbc;

	/** 최신 채널이 인덱스 순서를 쓴다. 이것이 이 티켓의 본체다. */
	@Test
	void recentChannelUsesIndexOrdering() {
		String plan = explain(FeedCandidateRepository.RECENT_SQL, params(100));

		assertThat(plan)
			.as("최신 채널이 ix_collection_feed의 정렬 순서를 쓰지 못한다. 계획:%n%s", plan)
			.contains(PRESORTED);
	}

	/**
	 * 팔로우 채널은 <b>{@code ix_collection_feed}를 쓰지 않는 것이 맞다.</b>
	 *
	 * <p>이 채널은 {@code follow}에서 출발해 {@code followee_member_id}로 Collection을 찾는다.
	 * 발행 전체를 훑는 것이 아니라 특정 회원들의 것만 보므로 올바른 인덱스는
	 * {@code ix_collection_member}이고, 마지막 정렬은 팔로우한 사람의 Collection 수만큼이라 싸다.
	 * 천만 건 실측에서도 이 계획으로 0.99ms였다(BI-38).
	 *
	 * <p>그래서 여기서 볼 것은 정렬 순서가 아니라 <b>전체 스캔으로 새지 않는지</b>다 —
	 * follow를 인덱스로 짚고 Collection을 회원 단위로 좁히는지. 이전에 이 자리에서
	 * {@code Presorted Key}를 단정했던 것은 채널의 성질을 잘못 본 것이었다.
	 */
	@Test
	void followedChannelNarrowsByFolloweeInsteadOfScanningAll() {
		String plan = explain(FeedCandidateRepository.FOLLOWED_SQL, params(80));

		assertThat(plan)
			.as("팔로우 채널이 follow를 기점으로 좁히지 않는다. 계획:%n%s", plan)
			.contains("follow")
			.contains("ix_collection_member");
		assertThat(plan)
			.as("팔로우 채널이 Collection 전체를 훑는다. 계획:%n%s", plan)
			.doesNotContain("Seq Scan on collection");
	}

	/**
	 * 정렬키에 표현식이 끼면 위 두 단정이 깨진다는 것을 같은 자리에서 보인다.
	 *
	 * <p>회귀 방지의 핵심이다. 이 테스트가 없으면 누가 {@code COALESCE}를 되돌려 놓아도 "왜
	 * 안 되는지"가 계획을 직접 읽어야만 드러난다.
	 */
	@Test
	void expressionSortKeyForecloseIndexOrdering() {
		String plain = "ORDER BY c.published_at DESC, c.id DESC";
		String withExpression = FeedCandidateRepository.RECENT_SQL.replace(plain,
			"ORDER BY COALESCE(c.published_at, c.created_at) DESC, c.id DESC");

		// 치환이 실제로 일어났는지 확인한다. 본체의 ORDER BY 문구가 바뀌면 위 replace가 조용히
		// no-op이 되고, 그러면 이 테스트가 "표현식을 넣었는데도 통과"라는 거짓 결과를 낸다.
		assertThat(withExpression)
			.as("RECENT_SQL의 ORDER BY 문구가 바뀌어 치환이 무효화됐다. 이 테스트를 함께 고칠 것")
			.doesNotContain(plain)
			.contains("COALESCE");

		String plan = explain(withExpression, params(100));

		assertThat(plan)
			.as("표현식 정렬키인데도 인덱스 순서를 썼다. 판별 기준을 다시 세워야 한다. 계획:%n%s", plan)
			.doesNotContain(PRESORTED);
	}

	/**
	 * 과거 플레이크가 났던 적재량(collection 20~100행)에서도 판정이 유지되는지 본다.
	 *
	 * <p>공유 테스트 DB의 행 수는 함께 도는 테스트에 따라 달라진다. 그 우연에 기대지 않으려면
	 * <b>실패했던 구간을 직접 만들어 두고</b> 단정해야 한다. 이 구간에서 플래너는 스캔만 막았을 때
	 * {@code ix_collection_member} + Merge Join으로 새어 나갔다.
	 */
	@Test
	void indexOrderingHoldsAtTheRowCountThatUsedToFlake() {
		// 회원과 Collection 수를 **함께** 늘린다. 회원 1명에 Collection 60개를 몰아 넣으면
		// member 쪽 카디널리티가 낮아 플래너가 다른 계획을 골라 실패 구간을 비껴간다 —
		// 실측으로 그 형태에서는 재현되지 않았다. 1:1로 만들어야 과거 실패 조건과 같아진다.
		for (int i = 0; i < 60; i++) {
			jdbc.update("""
				INSERT INTO core.collection
					(member_id, title, is_published, published_at, record_count)
				VALUES (:memberId, :title, true, now() - (:minutes || ' minutes')::interval, 3)
				""", Map.of("memberId", insertMember(), "title", "플레이크 구간 " + i, "minutes", i));
		}
		jdbc.getJdbcTemplate().execute("ANALYZE core.collection");
		jdbc.getJdbcTemplate().execute("ANALYZE core.member");

		String plan = explain(FeedCandidateRepository.RECENT_SQL, params(100));

		assertThat(plan)
			.as("플레이크가 났던 적재량에서 최신 채널이 인덱스 순서를 놓쳤다. 계획:%n%s", plan)
			.contains(PRESORTED)
			.contains("ix_collection_feed");
	}

	/**
	 * COALESCE 제거를 정당화하는 불변식 — 발행된 Collection은 {@code published_at}을 반드시 갖는다.
	 *
	 * <p>현재 데이터를 세는 것이 아니라 <b>DB가 막아 주는지</b>를 본다(V5
	 * {@code ck_collection_published_at}, BD-33). 현재 데이터만 확인하면 나중에 제약이 사라져도
	 * 통과한다. 이 제약이 없어지면 정렬키에서 COALESCE를 뺀 판단의 근거가 무너지므로,
	 * 그때 이 테스트가 먼저 깨져야 한다.
	 */
	@Test
	void publishedCollectionCannotHaveNullPublishedAt() {
		Map<String, Object> row = new HashMap<>();
		row.put("memberId", insertMember());

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO core.collection (member_id, title, is_published, published_at, record_count)
				VALUES (:memberId, '제약 확인', true, NULL, 1)
				""", row))
			.as("발행 상태인데 published_at이 비어 있는 행이 들어갔다. "
				+ "ck_collection_published_at이 사라졌다면 채널 정렬키 판단을 다시 봐야 한다")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertMember() {
		Long id = jdbc.queryForObject(
			"INSERT INTO core.member (created_at) VALUES (now()) RETURNING id", Map.of(), Long.class);
		return id == null ? 0L : id;
	}

	private Map<String, Object> params(int limit) {
		Map<String, Object> params = new HashMap<>();
		params.put("me", insertMember());
		params.put("limit", limit);
		return params;
	}

	/**
	 * 순서를 보존하는 Index Scan만 남기고 대안 경로를 막은 뒤 EXPLAIN한다.
	 *
	 * <p><b>{@code enable_seqscan}만 끄면 부족하다.</b> 플래너가 Bitmap Index Scan을 고르는데
	 * 그것은 힙 순서로 읽어 인덱스 정렬을 잃으므로, 정렬키가 올바른데도 전체 Sort가 붙어
	 * {@code Presorted Key}가 사라진다. 실제로 그 때문에 이 테스트가 공유 테스트 DB의 행 수에
	 * 따라 붙었다 떨어졌다 했다. 그래서 {@code enable_bitmapscan}도 함께 끈다.
	 *
	 * <p>둘을 끄면 남는 것은 정렬을 보존하는 Index Scan뿐이다. 그 상태에서 인덱스 순서를
	 * <b>쓸 수 있으면</b> Incremental Sort가 {@code Presorted Key}를 남기고, 정렬키가 표현식이면
	 * 여전히 전체 Sort가 붙는다 — 이것이 판별의 근거다.
	 *
	 * <p>세션이 아니라 실행 직후 되돌린다. 세션에 남기면 같은 커넥션을 물려받는 뒤 테스트의
	 * 계획까지 바꿔 놓는다.
	 */
	private String explain(String sql, Map<String, Object> params) {
		PLANNER_OFF.forEach(knob -> jdbc.getJdbcTemplate().execute("SET " + knob + " = off"));
		try {
			List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN " + sql, params);
			return rows.stream()
				.map(r -> String.valueOf(r.values().iterator().next()))
				.reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'),
					StringBuilder::append)
				.toString();
		} finally {
			PLANNER_OFF.forEach(knob -> jdbc.getJdbcTemplate().execute("RESET " + knob));
		}
	}
}
