package com.pinlog.pinlogback.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PublishedAtMigrationTests extends IntegrationContainerSupport {

	private static final String SCRATCH_DATABASE = "published_at_migration";

	@Test
	void backfillsExistingNullAndEnforcesDatabaseDefaultAndNotNull() throws Exception {
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

			try (ResultSet column = statement.executeQuery("""
				SELECT is_nullable, column_default
				FROM information_schema.columns
				WHERE table_schema = 'core'
				AND table_name = 'collection'
				AND column_name = 'published_at'
				""")) {
				assertThat(column.next()).isTrue();
				assertThat(column.getString("is_nullable")).isEqualTo("NO");
				assertThat(column.getString("column_default")).contains("now()");
			}

			try (ResultSet inserted = statement.executeQuery("""
				INSERT INTO core.collection (member_id, title)
				VALUES (1, 'DB 기본값')
				RETURNING published_at
				""")) {
				assertThat(inserted.next()).isTrue();
				assertThat(inserted.getObject("published_at", OffsetDateTime.class)).isNotNull();
			}
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
