package com.pinlog.pinlogback.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
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
// AI 연동 기본값이 테스트 밖으로 새지 않게 막는다.
//
// pinlog.ai.base-url 의 기본값은 로컬 편의를 위한 http://localhost:8000 인데, Context 를 만드는
// 통합 테스트는 대부분 @Transactional 이 아니라 실제로 커밋한다 → AFTER_COMMIT 리스너가 뜬다 →
// 로컬에 FastAPI 를 띄워 둔 채 테스트를 돌리면 진짜 요청이 나간다. Testcontainers 의 임시
// context_id 로 실제 임베딩 작업이 시작되어 dev DB 에 쓸모없는 행이 남고 임베딩 API 비용이
// 나간다. CI 에서는 연결 거부로 삼켜져 무해하므로 "로컬에서만 터지고 CI 는 조용한" 문제다.
// 127.0.0.1:1 은 즉시 연결 거부를 받는 주소라 타임아웃을 기다리지 않는다.
//
// internal-secret 도 채운다. 비워 두면 컨텍스트 기동마다 경고가 찍혀 봐야 할 로그를 덮는다.
//
// @DynamicPropertySource 가 아니라 @TestPropertySource 인 이유: 대역으로 실제 호출을 검증하는
// 테스트(ContextAiEnqueueTests)는 자기 @DynamicPropertySource 로 base-url 을 stub 포트로 덮어야
// 하는데, 두 쪽 다 @DynamicPropertySource 면 상위 클래스 쪽이 나중에 등록돼 하위를 덮어버린다
// (실측: 그 클래스 테스트 5개가 전부 "호출이 오지 않음"으로 깨졌다). DynamicValuesPropertySource
// 는 우선순위가 가장 높으므로, 기본값을 @TestPropertySource 로 한 단계 낮춰 두면 하위가 이긴다.
//
// 재스캔 주기도 늘린다. @EnableScheduling 은 전역이라 모든 @SpringBootTest 가 스케줄러를 함께
// 띄우는데, 5분 주기로 두면 컨텍스트를 오래 공유하는 스위트에서 회차가 배경에서 돌아 다른 테스트가
// 만든 상태 행을 건드린다. 재스캔 자체를 검증하는 테스트는 주기를 기다리지 않고 runOnce() 를 직접
// 부르므로(그래야 결정적이다) 이 값이 크면 배경 실행만 사라지고 검증은 그대로다.
//
// 끄지 않고 늘리는 이유: @Scheduled 등록 자체가 검증 대상이다(fixedDelay 인지, 전용 스케줄러를
// 쓰는지). 조건부로 끄면 그 계약을 볼 수 없다.
@TestPropertySource(properties = {
	"pinlog.ai.base-url=http://127.0.0.1:1",
	"pinlog.ai.internal-secret=test-internal-secret",
	"pinlog.ai.rescan.interval=PT1H"
})
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
