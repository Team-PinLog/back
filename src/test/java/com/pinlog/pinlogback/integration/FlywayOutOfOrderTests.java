package com.pinlog.pinlogback.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * BT-02 회귀 테스트 — AI 구간이 이미 적용된 DB에 백엔드 마이그레이션이 추가돼도 기동이 성공해야 한다.
 *
 * <p>버전 구간 소유(공통 V1 / 백엔드 V2~V99 / AI V100~V199)에서 AI 구간이 백엔드 구간보다 위에 있으므로,
 * 백엔드가 추가하는 번호는 항상 적용된 최대 버전(102)보다 낮다. Flyway는 기본적으로 이를 out-of-order로
 * 보고 거부하며, 그 거부는 {@code validate}가 아니라 부팅 시 실행되는 {@code migrate} 안에서 터진다.
 * 그래서 이 테스트도 {@code migrate()}로 검증한다 — 애플리케이션이 실제로 하는 일과 같다.
 *
 * <p>{@link FlywayMigrationTests}는 <b>빈 DB</b>에서 시작하므로 이 실패를 구조적으로 잡을 수 없다
 * (빈 DB에서는 V1 → V2 → V100 순서대로 적용되어 out-of-order가 발생하지 않는다). 그 공백을 이 클래스가 메운다.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlywayOutOfOrderTests extends PostgresContainerSupport {

	private static final String SCRATCH_DATABASE = "bt02_out_of_order";
	private static final String FIRST_BACKEND_VERSION = "2";
	private static final int BACKEND_RANGE_START = 2;
	private static final int BACKEND_RANGE_END = 99;

	@Autowired
	private Flyway flyway;

	@Test
	void applicationEnablesOutOfOrder() {
		assertThat(flyway.getConfiguration().isOutOfOrder())
			.as("구간 소유 구조를 유지하는 동안 out-of-order 없이는 백엔드 마이그레이션 추가가 기동을 깬다(BT-02)")
			.isTrue();
	}

	@Test
	void backendMigrationAppliesWhenAiMigrationsAreAlreadyApplied() throws Exception {
		recreateScratchDatabase();
		String url = scratchUrl();

		seedDatabaseWithAiMigrationsOnly(url);

		Flyway asApplicationWouldRun = Flyway.configure()
			.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.outOfOrder(flyway.getConfiguration().isOutOfOrder())
			.load();

		assertThatCode(asApplicationWouldRun::migrate)
			.as("V100~V102가 적용된 DB에 V%s를 추가한 뒤 기동하면 Flyway가 거부하면 안 된다", FIRST_BACKEND_VERSION)
			.doesNotThrowAnyException();

		MigrateResult reapplied = asApplicationWouldRun.migrate();
		assertThat(reapplied.migrationsExecuted)
			.as("앞선 migrate가 이미 적용을 마쳤으므로 두 번째 호출은 아무것도 적용하지 않는다")
			.isZero();
		assertThat(appliedVersions(url)).contains("1", FIRST_BACKEND_VERSION, "3", "100", "101", "102");
		assertThat(memberTableExists(url))
			.as("out-of-order로 적용된 백엔드 마이그레이션이 실제로 테이블을 만들었는지")
			.isTrue();
	}

	/**
	 * "AI 마이그레이션만 적용된 기존 DB"를 만든다. 전체를 적용한 뒤 백엔드 몫만 되돌리는 방식을 쓰는 이유는,
	 * 특정 버전만 골라 적용하는 {@code cherryPick}이 Flyway 상용 기능이라 community 판에서는 쓸 수 없기 때문이다.
	 * 되돌린 결과는 실제 상황과 같다 — 이력에 V1·V100~V102만 남고, 저장소의 백엔드 구간(V2~V99)은
	 * 아직 적용되지 않은 상태다.
	 *
	 * <p>백엔드 테이블은 이름을 나열하지 않고 "core 스키마에서 AI 소유(feed_event)가 아닌 전부"로
	 * 걷어낸다 — 백엔드 마이그레이션이 늘 때마다 이 목록을 따라 고쳐야 하면 그 사이 이 테스트가 항상
	 * 깨진다. 백엔드 테이블끼리 FK로 물려 있으므로 CASCADE로 지운다.
	 */
	private void seedDatabaseWithAiMigrationsOnly(String url) throws Exception {
		Flyway.configure()
			.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();

		try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
			Statement statement = connection.createStatement()) {
			for (String table : backendTables(connection)) {
				statement.execute("DROP TABLE IF EXISTS core." + table + " CASCADE");
			}
			statement.execute("DELETE FROM public.flyway_schema_history"
				+ " WHERE version IS NOT NULL AND version ~ '^[0-9]+$'"
				+ " AND CAST(version AS int) BETWEEN " + BACKEND_RANGE_START + " AND " + BACKEND_RANGE_END);
		}

		assertThat(appliedVersions(url))
			.as("재현 상태 전제: 백엔드 마이그레이션은 적용돼 있지 않아야 한다")
			.doesNotContain(FIRST_BACKEND_VERSION)
			.contains("1", "100", "101", "102");
	}

	private Set<String> backendTables(Connection connection) throws Exception {
		Set<String> tables = new HashSet<>();
		try (Statement statement = connection.createStatement();
			ResultSet rs = statement.executeQuery(
				"SELECT table_name FROM information_schema.tables"
					+ " WHERE table_schema = 'core' AND table_name <> 'feed_event'")) {
			while (rs.next()) {
				tables.add(rs.getString("table_name"));
			}
		}
		return tables;
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

	private Set<String> appliedVersions(String url) throws Exception {
		Set<String> versions = new HashSet<>();
		try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
			Statement statement = connection.createStatement();
			ResultSet rs = statement.executeQuery(
				"SELECT version FROM public.flyway_schema_history WHERE success AND version IS NOT NULL")) {
			while (rs.next()) {
				versions.add(rs.getString("version"));
			}
		}
		return versions;
	}

	private boolean memberTableExists(String url) throws Exception {
		try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
			ResultSet rs = connection.getMetaData().getTables(null, "core", "member", null)) {
			return rs.next();
		}
	}
}
