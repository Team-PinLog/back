package com.pinlog.pinlogback.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlywayMigrationTests extends PostgresContainerSupport {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Flyway flyway;

	@Autowired
	DataSource dataSource;

	@Test
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
	void coreDomainTablesAreCreatedByBackendMigration() {
		assertThat(tableNamesIn("core")).contains(
			"place",
			"record",
			"context",
			"collection",
			"collection_record",
			"follow"
		);
	}

	@Test
	void coreDomainUniqueAndLookupIndexesExist() {
		assertThat(indexNamesIn("core")).contains(
			"uq_place_kakao",
			"uq_record_active",
			"uq_colrec_active",
			"uq_follow_active",
			"ix_place_lat_lng",
			"ix_record_member",
			"ix_record_place",
			"ix_context_record",
			"ix_context_member",
			"ix_colrec_collection",
			"ix_colrec_record",
			"ix_collection_member",
			"ix_collection_feed",
			"ix_follow_follower",
			"ix_follow_followee"
		);
	}

	@Test
	void activeRowUniqueIndexesArePartial() {
		Set<String> partialIndexes = new HashSet<>(jdbcTemplate.queryForList(
			"SELECT indexname FROM pg_indexes WHERE schemaname = 'core'"
				+ " AND indexdef LIKE '%WHERE (deleted_at IS NULL)%'",
			String.class));

		assertThat(partialIndexes).contains("uq_record_active", "uq_colrec_active", "uq_follow_active");
		assertThat(partialIndexes).doesNotContain("uq_place_kakao");
	}

	@Test
	void coreDomainCheckConstraintsExist() {
		Set<String> checkNames = new HashSet<>(jdbcTemplate.queryForList(
			"SELECT conname FROM pg_constraint c"
				+ " JOIN pg_namespace n ON n.oid = c.connamespace"
				+ " WHERE n.nspname = 'core' AND c.contype = 'c'",
			String.class));

		assertThat(checkNames).contains(
			"ck_follow_self",
			"ck_context_body",
			"ck_collection_title",
			"ck_collection_count",
			"ck_place_lat",
			"ck_place_lng"
		);
	}

	@Test
	void contextOriginCreatedAtIsNotNull() {
		String isNullable = jdbcTemplate.queryForObject(
			"SELECT is_nullable FROM information_schema.columns"
				+ " WHERE table_schema = 'core' AND table_name = 'context'"
				+ " AND column_name = 'origin_created_at'",
			String.class);

		assertThat(isNullable).isEqualTo("NO");
	}

	@Test
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
