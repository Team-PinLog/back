package com.pinlog.pinlogback.domain.auth;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 공급자 연결 해제 호출에 쓰는 HTTP 클라이언트(BD-48).
 *
 * <p>설정 클래스를 {@code global/config}가 아니라 소비자와 같은 도메인에 두는 기준은
 * {@code AiIntegrationConfig}와 같다 — 설정과 그 설정이 조립하는 구현이 떨어져 있으면 한쪽만
 * 고치게 된다.
 *
 * <p>세 공급자가 인스턴스를 공유한다. AI 연동이 {@code process}·{@code search}로 Bean을 나눈 이유는
 * <b>타임아웃과 실패 정책이 달라서</b>인데, 여기 셋은 같은 왕복의 마지막 한 걸음이고 실패 정책도
 * 하나다(실패하면 탈퇴 전체가 실패한다). {@code baseUrl}을 두지 않는 것도 그래서다 — 호스트가
 * 공급자마다 다르므로 각 구현이 절대 URI를 쓴다.
 *
 * <p>타임아웃을 설정 키로 열지 않는다. 사용자가 탈퇴 버튼 뒤에서 기다리는 구간이라 값이 커질 수
 * 없고, 운영 중에 조정할 이유가 생기면 그때 여는 편이 낫다.
 */
@Configuration
public class SocialUnlinkConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	public RestClient socialUnlinkRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return RestClient.builder()
			.requestFactory(requestFactory)
			.build();
	}
}
