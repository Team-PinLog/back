package com.pinlog.pinlogback.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
// 컨테이너는 JVM 전체가 공유하는 싱글턴이라 닫지 않는다. try-with-resources로 감싸면 첫
// 테스트 클래스가 끝날 때 죽어 나머지가 죽은 포트를 보게 된다. 정리는 Ryuk가 JVM 종료 시 한다.
@SuppressWarnings("resource")
public abstract class IntegrationContainerSupport {

	/** {@code org.testcontainers.containers.PostgreSQLContainer}는 2.x에서 deprecated다. */
	@ServiceConnection
	protected static final PostgreSQLContainer POSTGRES =
		new PostgreSQLContainer(
			DockerImageName.parse("pgvector/pgvector:0.8.5-pg16")
				.asCompatibleSubstituteFor("postgres")
		);

	/** Testcontainers 2.0.5에 Redis 전용 모듈이 없다. {@code @ServiceConnection}은 이미지 이름으로 붙는다. */
	@ServiceConnection
	protected static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7.4.5-alpine")).withExposedPorts(6379);

	static {
		// JVM 전체에서 한 번만 띄운다(Testcontainers 싱글턴 컨테이너 패턴).
		//
		// @Container를 일부러 붙이지 않았다. 그 애노테이션을 붙이면 JUnit5 확장이 그것을 선언한
		// 테스트 클래스의 afterAll에서 컨테이너를 멈춘다. 여기 하위 클래스들은 모두 이 static
		// 인스턴스 하나를 공유하므로, 클래스마다 @Container를 다시 선언하면 클래스가 끝날 때마다
		// 공유 컨테이너가 멈췄다 다시 떠서 매핑 포트가 바뀐다. 그러면 @SpringBootTest 설정이 같아
		// 캐시된 컨텍스트를 재사용하는 뒤 클래스가 죽은 포트를 가리키는 DataSource를 물고, 모든 DB
		// 호출이 연결 타임아웃으로 실패한다(BT-01).
		//
		// 여기서 수동으로 시작하면 컨테이너 하나가 실행 내내 살아 있고, 정리는 JVM 종료 시 Ryuk가 한다.
		POSTGRES.start();
		REDIS.start();
	}
}
