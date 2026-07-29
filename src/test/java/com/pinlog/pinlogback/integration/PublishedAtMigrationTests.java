package com.pinlog.pinlogback.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PublishedAtMigrationTests extends IntegrationContainerSupport {

	private static final String SCRATCH_DATABASE = "published_at_migration";

	/** {@code core.collection}이 만들어지는 버전. 제약 이전에 존재하던 행을 심을 수 있는 가장 이른 시점이다. */
	private static final String COLLECTION_CREATED_VERSION = "3";

	/** 이 테스트가 검증하는 마이그레이션. */
	private static final String PUBLISHED_AT_VERSION = "5";

	@Test
	void backfillsExistingNullAndEnforcesPublishedRowsCarryTimestamp() throws Exception {
		recreateScratchDatabase();
		String url = scratchUrl();

		Flyway.configure()
			.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.target(COLLECTION_CREATED_VERSION)
			.load()
			.migrate();

		try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO core.member DEFAULT VALUES");
			statement.execute("""
				INSERT INTO core.collection (member_id, title, published_at)
				VALUES (1, '기존 NULL', NULL)
				""");
		}

		// 행을 심는 시점에는 제약도 백필도 아직 없다. 이 단언이 없으면 "V3까지만 적용"이라는 전제가
		// 주석으로만 남고, 사이에 다른 마이그레이션이 끼어들어도 조용히 통과한다.
		assertThat(appliedVersions(url))
			.as("NULL 행을 심는 시점의 DB 상태")
			.containsExactlyInAnyOrder("1", "2", COLLECTION_CREATED_VERSION);

		Flyway.configure()
			.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.outOfOrder(true)
			.load()
			.migrate();

		// 뒤따르는 단언이 보는 상태를 만든 것이 이 실행의 V5라는 근거. V4(social_account)가 사이에
		// 끼면서 "최신까지 적용했다"만으로는 V5가 실제로 돌았는지 알 수 없게 됐다.
		assertThat(appliedVersions(url))
			.as("이번 마이그레이션에서 V%s가 실제로 적용됐다", PUBLISHED_AT_VERSION)
			.contains(PUBLISHED_AT_VERSION);

		try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
			try (ResultSet row = statement.executeQuery("""
				SELECT created_at, published_at
				FROM core.collection
				WHERE title = '기존 NULL'
				""")) {
				assertThat(row.next()).isTrue();
				assertThat(row.getObject("published_at", OffsetDateTime.class))
					.isEqualTo(row.getObject("created_at", OffsetDateTime.class));
			}

			try (ResultSet constraint = statement.executeQuery("""
				SELECT pg_get_constraintdef(oid) AS definition
				FROM pg_constraint
				WHERE conrelid = 'core.collection'::regclass
				AND conname = 'ck_collection_published_at'
				""")) {
				assertThat(constraint.next()).isTrue();
				assertThat(constraint.getString("definition"))
					.contains("NOT is_published")
					.contains("published_at IS NOT NULL");
			}

			// 컬럼은 nullable로 남는다 — 제약은 발행된 행에만 건다.
			try (ResultSet column = statement.executeQuery("""
				SELECT is_nullable
				FROM information_schema.columns
				WHERE table_schema = 'core'
				AND table_name = 'collection'
				AND column_name = 'published_at'
				""")) {
				assertThat(column.next()).isTrue();
				assertThat(column.getString("is_nullable")).isEqualTo("YES");
			}

			// 미발행 행은 발행 시각이 없어도 된다 — 비공개 생성이 들어와도 제약을 풀 필요가 없다.
			statement.execute("""
				INSERT INTO core.collection (member_id, title, is_published, published_at)
				VALUES (1, '미발행', false, NULL)
				""");

			// 미발행 행이 과거 발행 시각을 남겨도 된다 — 쌍조건이었다면 여기서 거부된다.
			// 함의 한 방향이므로 발행 취소가 들어와도 유효하다.
			statement.execute("""
				INSERT INTO core.collection (member_id, title, is_published, published_at)
				VALUES (1, '발행 취소', false, now())
				""");

			// 발행된 행이 발행 시각을 빠뜨리면 거부된다 — 그럴듯한 값으로 덮지 않고 실패한다.
			assertThatThrownBy(() -> statement.execute("""
				INSERT INTO core.collection (member_id, title, is_published, published_at)
				VALUES (1, '발행인데 시각 없음', true, NULL)
				"""))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ck_collection_published_at");
		}
	}

	private void recreateScratchDatabase() throws Exception {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + SCRATCH_DATABASE);
			statement.execute("CREATE DATABASE " + SCRATCH_DATABASE);
		}
	}

	private String scratchUrl() {
		return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
			+ "/" + SCRATCH_DATABASE;
	}

	private Connection connect(String url) throws Exception {
		return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private Set<String> appliedVersions(String url) throws Exception {
		Set<String> versions = new HashSet<>();
		try (Connection connection = connect(url);
			Statement statement = connection.createStatement();
			ResultSet rs = statement.executeQuery(
				"SELECT version FROM public.flyway_schema_history WHERE success AND version IS NOT NULL")) {
			while (rs.next()) {
				versions.add(rs.getString("version"));
			}
		}
		return versions;
	}
}
