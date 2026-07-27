package com.pinlog.pinlogback.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Flyway 마이그레이션")
class FlywayMigrationTests extends PostgresContainerSupport {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	@Autowired
	DataSource dataSource;

	@Test
	@DisplayName("빈 PostgreSQL에 모든 마이그레이션을 적용한다")
	void appliesAllMigrationsToEmptyPostgres() {
		Set<String> versions = java.util.Arrays.stream(flyway.info().applied())
			.map(info -> info.getVersion().getVersion())
			.collect(Collectors.toSet());

		assertThat(versions).contains("1", "100", "101", "102");
		assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('core','ai')"))
			.isEqualTo(2);
		assertThat(count("SELECT count(*) FROM pg_extension WHERE extname = 'vector'"))
			.isEqualTo(1);
		assertThat(count(
			"SELECT count(*) FROM information_schema.tables"
				+ " WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'"))
			.isEqualTo(1);
		assertThat(tableNamesIn("ai")).contains(
			"keyword_preset",
			"context_ai_state",
			"context_embedding",
			"context_keyword",
			"context_keyword_analysis"
		);
		assertThat(tableNamesIn("core")).contains("feed_event");
		assertThat(indexNamesIn("ai")).contains(
			"idx_context_embedding_user_active",
			"idx_context_embedding_record",
			"idx_context_ai_state_embedding",
			"idx_context_ai_state_keyword",
			"idx_context_keyword_keyword"
		);
		assertThat(indexNamesIn("core")).contains(
			"ix_feed_event_penalty",
			"ix_feed_event_request",
			"ix_feed_event_created"
		);
	}

	@Test
	@DisplayName("백엔드 마이그레이션이 core.member를 생성한다")
	void memberTableIsCreatedByBackendMigration() throws Exception {
		try (Connection connection = dataSource.getConnection();
			ResultSet rs = connection.getMetaData().getColumns(null, "core", "member", null)) {
			Set<String> columns = new HashSet<>();
			while (rs.next()) {
				columns.add(rs.getString("COLUMN_NAME"));
			}
			assertThat(columns).containsExactlyInAnyOrder("id", "created_at", "deleted_at");
		}
	}

	@Test
	@DisplayName("백엔드 마이그레이션이 core.social_account를 생성한다")
	void socialAccountTableIsCreatedByBackendMigration() throws Exception {
		try (Connection connection = dataSource.getConnection();
			ResultSet rs = connection.getMetaData().getColumns(null, "core", "social_account", null)) {
			Set<String> columns = new HashSet<>();
			while (rs.next()) {
				columns.add(rs.getString("COLUMN_NAME"));
			}
			assertThat(columns).containsExactlyInAnyOrder(
				"id", "member_id", "provider", "provider_user_id", "email", "created_at", "deleted_at");
		}
	}

	@Test
	@DisplayName("소셜 계정 유니크 인덱스는 활성행에만 적용된다")
	void socialAccountUniqueIndexAppliesToActiveRowsOnly() {
		// 전체 유니크로 정의하면 탈퇴 후 같은 소셜 계정으로 재가입할 수 없다(07_ERD 4.1).
		String indexDefinition = jdbcTemplate.queryForObject(
			"SELECT indexdef FROM pg_indexes WHERE schemaname = 'core' AND indexname = ?",
			String.class,
			"ux_social_account_provider_user");

		assertThat(indexDefinition)
			.contains("UNIQUE")
			.contains("provider")
			.contains("provider_user_id")
			.contains("deleted_at IS NULL");
	}

	private int count(String sql) {
		Integer result = jdbcTemplate.queryForObject(sql, Integer.class);
		return result == null ? 0 : result;
	}

	private Set<String> tableNamesIn(String schemaName) {
		return jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
				String.class,
				schemaName
			)
			.stream()
			.collect(Collectors.toSet());
	}

	private Set<String> indexNamesIn(String schemaName) {
		return jdbcTemplate.queryForList(
				"SELECT indexname FROM pg_indexes WHERE schemaname = ?",
				String.class,
				schemaName
			)
			.stream()
			.collect(Collectors.toSet());
	}
}
