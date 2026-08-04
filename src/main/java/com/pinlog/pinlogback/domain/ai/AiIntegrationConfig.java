package com.pinlog.pinlogback.domain.ai;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

import com.pinlog.pinlogback.domain.ai.queue.AiQueueProperties;
import com.pinlog.pinlogback.domain.ai.service.AiRescanProperties;

/**
 * FastAPI 연동에 필요한 인프라 조립. 설정 클래스를 {@code global/config}가 아니라 소비자와 같은
 * 패키지에 두는 기준은 {@code docs/development/package-structure.md}의 보안 설정과 같다 — 설정과
 * 그 설정이 조립하는 구현이 떨어져 있으면 한쪽만 고치게 된다.
 *
 * <p>{@link EnableScheduling}이 여기 있는 것도 같은 사정이다. 애플리케이션 <b>전역</b> 스위치인데,
 * 켜야 하는 이유가 이 연동(재스캔)에만 있다. {@code global/config}로 올리면 스위치와 그 스위치의
 * 유일한 소비자가 떨어져 앉는다. 한때 여기 있던 {@code @EnableAsync}와 {@code aiCallExecutor}
 * (커밋 후 FastAPI 호출용 인메모리 큐)는 Kafka 발행으로 대체되어 사라졌다(BD-48).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({AiProperties.class, AiRescanProperties.class, AiQueueProperties.class})
public class AiIntegrationConfig {

	/**
	 * Context→AI 요청의 본 토픽(BD-48). 재시도 토픽과 DLT는 {@code @RetryableTopic}이 여기서
	 * 파생해 만들므로 본 토픽만 선언한다. 파티션 1인 이유: 처리량이 Record 저장 빈도(초당 수 건)를
	 * 넘지 않고, 컨슈머도 단일 인스턴스라 병렬화로 얻을 것이 없다. 복제 1은 단일 브로커 전제다 —
	 * 브로커 구성이 커지면 INFRA와 함께 올린다.
	 */
	@Bean
	public NewTopic contextAiProcessTopic(AiQueueProperties queueProperties) {
		return TopicBuilder.name(queueProperties.topic()).partitions(1).replicas(1).build();
	}

	/** {@code process}용 전용 인스턴스(AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 2·3장). */
	@Bean
	public RestClient aiProcessRestClient(AiProperties properties) {
		return restClient(properties.baseUrl(), properties.process());
	}

	/**
	 * {@code search}용 전용 인스턴스다. <b>Bean을 나눈 이유는 타임아웃과 실패 정책이 다르기
	 * 때문</b>이다(명세 2·3장: process 3s / search 5s). 검색은 질의 임베딩 생성과 벡터 검색이 응답 경로
	 * 안에 있어 더 오래 걸리는데, 그 값을 process에 맞추면 정상 검색이 잘리고, 반대로 맞추면 접수 확인만
	 * 받는 process가 장애 시 사용자 스레드를 5초씩 붙잡는다.
	 *
	 * <p>타입이 같은 {@link RestClient} Bean이 둘이 되므로 <b>양쪽 주입부 모두</b>
	 * {@code @Qualifier}로 이름을 지정해야 한다 — 붙이지 않으면 기동 시
	 * {@code NoUniqueBeanDefinitionException}이다.
	 */
	@Bean
	public RestClient aiSearchRestClient(AiProperties properties) {
		return restClient(properties.baseUrl(), properties.search());
	}

	/**
	 * 커넥션 풀이 있는 factory를 쓰지 않는 이유: 두 호출 모두 작은 본문을 주고받고 끝나는 짧은
	 * 요청이고 빈도도 Context 생성·사용자 검색 빈도를 넘지 않는다. 풀링 클라이언트를 붙이면 의존성만
	 * 늘고 그만큼 <b>튜닝할 것도 늘어난다.</b>
	 *
	 * <p>Boot가 주는 {@code RestClient.Builder}를 주입받지 않고 {@link RestClient#builder()}로
	 * 시작한다. 그 Bean은 별도 starter가 있어야 생기는데, 여기서 필요한 것은 기본 메시지 컨버터가
	 * 붙은 빈 builder 하나뿐이라 <b>연동 하나 때문에 의존성을 늘릴 이유가 없다.</b>
	 */
	private RestClient restClient(String baseUrl, AiProperties.Timeouts timeouts) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(timeouts.connectTimeout());
		requestFactory.setReadTimeout(timeouts.readTimeout());
		return RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.build();
	}

	/**
	 * 재스캔 회차를 돌리는 스케줄러(AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 3장).
	 * <b>Boot의 기본 단일 스레드 스케줄러를 쓰지 않는다.</b>
	 *
	 * <p>Bean 이름이 {@code taskScheduler}인 것은 의도다. Spring은 스케줄러를 <b>작업별로 고르지
	 * 않고</b> {@code ScheduledTaskRegistrar} 단위로 하나 고르므로, {@code @Scheduled} 메서드가
	 * "자기 스케줄러"를 지목할 방법이 없다. 이름을 다르게 두면 타입이 유일할 때만 해석되고, 두 번째
	 * {@link TaskScheduler} Bean이 생기는 순간 조용히 로컬 단일 스레드 실행자로 떨어진다.
	 *
	 * <p>따라서 이것은 <b>애플리케이션의 스케줄러</b>이며, 스레드 이름이 {@code ai-rescan-}인 것은
	 * 지금 이 작업이 유일한 입주자라는 사실을 스레드 덤프에서 읽히게 하려는 것이다. 두 번째 배치가
	 * 생기면 이름을 중립적으로 바꾸거나 그 배치에 자기 registrar를 주는 판단이 함께 필요하다(BD-40).
	 *
	 * <p>스레드가 2개인 이유: 회차는 {@code fixedDelay}라 겹치지 않으므로 하나로 충분하지만, 두 번째
	 * 스케줄 작업이 붙었을 때 <b>재스캔 한 회차가 그 작업을 굶기는 것</b>을 막는 여유다. 한 회차는
	 * 배치 크기만큼의 HTTP 호출을 순차로 내보내 길어질 수 있다.
	 */
	@Bean
	public TaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("ai-rescan-");
		scheduler.setPoolSize(2);
		// 종료를 기다리지 않는다(기본값). 기다리게 하면 진행 중인 FastAPI 호출의 read-timeout만큼
		// 종료가 늦어져 graceful shutdown 예산(20s)을 잠식한다. 중단된 회차는 다음 회차가 다시 집는다.
		scheduler.initialize();
		return scheduler;
	}

}
