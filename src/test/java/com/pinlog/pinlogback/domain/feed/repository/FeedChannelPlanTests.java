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
 * <p><b>SQL 사본을 두지 않는다.</b> 리포지터리의 상수를 직접 EXPLAIN한다. 테스트가 사본을 들면
 * 본체만 바뀌었을 때 통과해 거짓 안심을 준다. 그래서 {@code RECENT_SQL}·{@code FOLLOWED_SQL}이
 * package-private이며, 이 테스트가 그 가시성의 유일한 이유다.
 */
@SpringBootTest
class FeedChannelPlanTests extends IntegrationContainerSupport {

	private static final String PRESORTED = "Presorted Key";

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

	/** 팔로우 채널도 같은 인덱스에 의존한다. 행이 적어 실해가 작을 뿐 원인은 동일하다. */
	@Test
	void followedChannelUsesIndexOrdering() {
		String plan = explain(FeedCandidateRepository.FOLLOWED_SQL, params(80));

		assertThat(plan)
			.as("팔로우 채널이 ix_collection_feed의 정렬 순서를 쓰지 못한다. 계획:%n%s", plan)
			.contains(PRESORTED);
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
	 * {@code enable_seqscan}을 세션이 아니라 트랜잭션 범위로 끈다. 세션으로 끄면 같은 커넥션을
	 * 물려받는 뒤 테스트의 계획까지 바꿔 놓는다.
	 */
	private String explain(String sql, Map<String, Object> params) {
		jdbc.getJdbcTemplate().execute("SET enable_seqscan = off");
		try {
			List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN " + sql, params);
			return rows.stream()
				.map(r -> String.valueOf(r.values().iterator().next()))
				.reduce(new StringBuilder(), (sb, line) -> sb.append(line).append('\n'),
					StringBuilder::append)
				.toString();
		} finally {
			jdbc.getJdbcTemplate().execute("RESET enable_seqscan");
		}
	}
}
