package com.pinlog.pinlogback.integration;

import java.util.UUID;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
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
// 큐 재시도도 줄인다. 기본값(4회, 1s부터 지수 백오프)이면 재시도 체인 소진(DLT 격리)을 검증하는
// 테스트가 회차마다 합계 7초를 기다린다. 줄여도 검증 대상(체인을 타고 DLT에 도달한다)은 같다.
//
// Hikari 풀도 줄인다. 스위트가 캐시하는 Spring 컨텍스트 하나마다 풀(기본 10)이 통째로 살아
// 남는데, Kafka 큐 도입으로 전용 컨텍스트가 하나 더 생기며 Postgres 기본 max_connections(100)를
// 넘겼다 — 실제로 too many clients로 죽었다. 테스트는 순차 실행이라 5로도 남는다.
@TestPropertySource(properties = {
	"pinlog.ai.base-url=http://127.0.0.1:1",
	"pinlog.ai.internal-secret=test-internal-secret",
	"pinlog.ai.rescan.interval=PT1H",
	"pinlog.ai.queue.retry-attempts=3",
	"pinlog.ai.queue.retry-initial-delay-ms=100",
	"spring.datasource.hikari.maximum-pool-size=5"
})
public abstract class IntegrationContainerSupport {

	/**
	 * 큐 토픽·그룹을 <b>Spring 컨텍스트마다</b> 격리한다. 캐시된 컨텍스트들은 스위트가 끝날 때까지
	 * 살아서 컨슈머를 계속 돌리는데, 토픽·그룹을 공유하면 새 컨텍스트가 뜰 때마다 리밸런스가 나고
	 * 커밋 안 된 offset이 재전달되어 <b>다른 클래스의 "호출이 없어야 한다" 검증 구간에</b> 남의
	 * 호출이 흘러든다(실측: rollingBackTheTransactionNeverReachesFastApi가 그렇게 깨졌다).
	 * 토픽이 갈리면 컨슈머가 서로의 메시지를 볼 수 없어 재생·리밸런스 간섭이 구조적으로 사라진다.
	 *
	 * <p>이 메서드는 컨텍스트 생성 시 한 번 돌므로 suffix는 컨텍스트 단위로 고정된다. 같은 설정을
	 * 공유해 컨텍스트를 재사용하는 클래스들은 같은 토픽을 이어 쓴다 — 그것이 캐시의 의미다.
	 */
	@DynamicPropertySource
	static void isolatedQueuePerContext(DynamicPropertyRegistry registry) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		registry.add("pinlog.ai.queue.topic", () -> "context-ai.process-test-" + suffix);
		registry.add("pinlog.ai.queue.group", () -> "pinlog-back-test-" + suffix);
	}

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

	/** {@code compose.yaml}의 kafka와 같은 태그. Context→AI 큐(BD-48)가 이 브로커를 쓴다. */
	@ServiceConnection
	protected static final KafkaContainer KAFKA =
		new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));

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
		KAFKA.start();
	}

}
