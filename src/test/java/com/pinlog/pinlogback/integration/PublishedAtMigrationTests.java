package com.pinlog.pinlogback.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PublishedAtMigrationTests extends IntegrationContainerSupport {

	private static final String SCRATCH_DATABASE = "published_at_migration";

	@Test
	void backfillsExistingNullAndEnforcesPublishedRowsCarryTimestamp() throws Exception {
		recreateScratchDatabase();
		String url = scratchUrl();

		Flyway.configure()
			.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.target("3")
			.load()
			.migrate();

		try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO core.member DEFAULT VALUES");
			statement.execute("""
				INSERT INTO core.collection (member_id, title, published_at)
				VALUES (1, '기존 NULL', NULL)
				""");
		}

		Flyway.configure()
			.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.outOfOrder(true)
			.load()
			.migrate();

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
}
