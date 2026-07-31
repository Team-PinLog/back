package com.pinlog.pinlogback.domain.ai;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

import com.pinlog.pinlogback.domain.ai.service.AiRescanProperties;
import com.pinlog.pinlogback.domain.ai.service.EmbeddingInputProperties;

/**
 * FastAPI 연동에 필요한 인프라 조립. 설정 클래스를 {@code global/config}가 아니라 소비자와 같은
 * 패키지에 두는 기준은 {@code docs/development/package-structure.md}의 보안 설정과 같다 — 설정과
 * 그 설정이 조립하는 구현이 떨어져 있으면 한쪽만 고치게 된다.
 *
 * <p>{@link EnableScheduling}이 여기 있는 것은 {@link EnableAsync}와 같은 사정이다. 둘 다 애플리케이션
 * <b>전역</b> 스위치인데, 켜야 하는 이유가 이 연동에만 있다. {@code global/config}로 올리면 스위치와
 * 그 스위치의 유일한 소비자가 떨어져 앉는다.
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({AiProperties.class, AiRescanProperties.class, EmbeddingInputProperties.class})
public class AiIntegrationConfig {

	private static final Logger log = LoggerFactory.getLogger(AiIntegrationConfig.class);

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

	/**
	 * AI 호출 전용 풀(명세 4.2). 공용 executor를 쓰지 않는 이유는 FastAPI 장애가 다른 비동기 작업까지
	 * 굶기지 않게 하기 위해서다.
	 *
	 * <p><b>큐가 차면 버린다.</b> {@code CallerRunsPolicy}를 쓰면 요청 스레드가 외부 호출을 대신
	 * 수행해 FastAPI 장애가 그대로 Core 처리량 저하가 된다. 버려진 요청은 {@code PENDING}으로 남아
	 * 재스캔 대상이 되므로 유실이 아니다 — 그래서 버리는 쪽이 안전하다.
	 */
	@Bean
	public Executor aiCallExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("ai-call-");
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(100);
		// 버린 사실은 남긴다. 조용히 버리면 "왜 PENDING만 쌓이는가"를 알 길이 없다. 예외를 던지는
		// AbortPolicy는 쓰지 않는다 — 커밋 이후 리스너를 타고 올라가 정상 응답을 오류로 만든다.
		executor.setRejectedExecutionHandler((task, pool) ->
			log.debug("AI 호출 큐 포화로 요청을 버렸다. PENDING이 남아 재스캔이 다시 집는다."));
		executor.initialize();
		return executor;
	}
}
