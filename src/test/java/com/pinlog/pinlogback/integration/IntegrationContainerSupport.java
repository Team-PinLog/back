package com.pinlog.pinlogback.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트가 기대는 외부 의존 전부.
 *
 * <p>담고 있는 것을 이름에 나열하지 않는다. 나열하면 의존이 늘 때마다 클래스 이름과 모든 상속
 * 선언이 따라 바뀐다. 새 의존이 생기면 여기에 컨테이너를 하나 더 추가하면 된다.
 *
 * <p>이미지 태그는 {@code compose.yaml}과 맞춘다. 로컬과 CI가 다른 판을 쓰면 재현되지 않는
 * 차이가 생긴다.
 *
 * <p>Redis를 쓰지 않는 테스트에도 항상 띄우는 이유: 로그인 성공이 Refresh {@code jti}를 Redis에
 * 쓰므로 인증이 걸린 경로는 대부분 Redis를 탄다. 안 띄우면 <b>"이 테스트에 Redis가 필요한가"를
 * 매번 판단해야 하고, 틀리면 증상이 엉뚱한 곳에서 나온다</b> — 실제로 토큰 발급을 붙였을 때
 * 콜백 테스트가 그렇게 깨졌다. 쓰지 않는 테스트가 무는 비용은 JVM당 컨테이너 하나뿐이다.
 */
public abstract class IntegrationContainerSupport {

	@ServiceConnection
	protected static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>(
			DockerImageName.parse("pgvector/pgvector:0.8.5-pg16")
				.asCompatibleSubstituteFor("postgres")
		);

	@ServiceConnection
	protected static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7.4.5-alpine")).withExposedPorts(6379);

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
		REDIS.start();
	}
}
