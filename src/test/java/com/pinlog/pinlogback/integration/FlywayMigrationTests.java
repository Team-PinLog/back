package com.pinlog.pinlogback.integration;

import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlywayMigrationTests extends PostgresContainerSupport {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres = POSTGRES;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway flyway;

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
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'"))
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
