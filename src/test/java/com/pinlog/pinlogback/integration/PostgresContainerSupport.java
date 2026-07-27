package com.pinlog.pinlogback.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class PostgresContainerSupport {

	@ServiceConnection
	protected static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(
			DockerImageName.parse("pgvector/pgvector:0.8.5-pg16")
				.asCompatibleSubstituteFor("postgres")
		);

	static {
		// Started once for the whole JVM (Testcontainers "singleton container" pattern).
		// Deliberately NOT annotated with @Container: that annotation makes JUnit5's
		// Testcontainers extension stop the container in afterAll of whichever test class
		// declares it. Every subclass here shares this same static instance, so redeclaring
		// @Container per subclass caused each class to stop-then-restart the shared
		// container, changing its mapped port. A later test class with an identical
		// @SpringBootTest configuration then reused a cached Spring context whose DataSource
		// still pointed at the old, now-dead port, failing every DB call with a connection
		// timeout. Manual start here keeps one container alive for the whole run; Ryuk
		// reaps it when the JVM exits.
		POSTGRES.start();
	}
}
