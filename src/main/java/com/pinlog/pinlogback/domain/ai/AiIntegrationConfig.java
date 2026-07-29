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

	/**
	 * {@code process}용 전용 인스턴스다. {@code search}는 타임아웃과 실패 정책이 달라 Bean을
	 * 공유하지 않는다(AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 2·3장).
	 *
	 * <p>커넥션 풀이 있는 factory를 쓰지 않는 이유: 이 호출은 본문 없는 202를 받고 끝나는 짧은
	 * 요청이고 빈도도 Context 생성 빈도를 넘지 않는다. 풀링 클라이언트를 붙이면 의존성만 늘고
	 * 그만큼 <b>튜닝할 것도 늘어난다.</b>
	 *
	 * <p>Boot가 주는 {@code RestClient.Builder}를 주입받지 않고 {@link RestClient#builder()}로
	 * 시작한다. 그 Bean은 별도 starter가 있어야 생기는데, 여기서 필요한 것은 기본 메시지 컨버터가
	 * 붙은 빈 builder 하나뿐이라 <b>연동 하나 때문에 의존성을 늘릴 이유가 없다.</b>
	 */
	@Bean
	public RestClient aiProcessRestClient(AiProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.process().connectTimeout());
		requestFactory.setReadTimeout(properties.process().readTimeout());
		return RestClient.builder()
			.baseUrl(properties.baseUrl())
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
