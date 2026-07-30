package com.pinlog.pinlogback.domain.ai;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/**
 * FastAPI 연동에 필요한 인프라 조립. 설정 클래스를 {@code global/config}가 아니라 소비자와 같은
 * 패키지에 두는 기준은 {@code docs/development/package-structure.md}의 보안 설정과 같다 — 설정과
 * 그 설정이 조립하는 구현이 떨어져 있으면 한쪽만 고치게 된다.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(AiProperties.class)
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
