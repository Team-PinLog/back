package com.pinlog.pinlogback.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis까지 필요한 테스트의 기반. Refresh 회전이 Redis에 저장되므로 인증 계약 검증에 필요하다.
 *
 * <p>컨테이너 수명 관리는 {@link PostgresContainerSupport}와 같은 이유로 수동이다 —
 * {@code @Container}를 붙이면 클래스마다 중지·재시작이 일어나 매핑 포트가 바뀌고,
 * 캐시된 Spring 컨텍스트가 죽은 포트를 가리키게 된다.
 */
public abstract class PostgresRedisContainerSupport extends PostgresContainerSupport {

	@ServiceConnection
	protected static final GenericContainer<?> REDIS =
		new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	static {
		REDIS.start();
	}
}
