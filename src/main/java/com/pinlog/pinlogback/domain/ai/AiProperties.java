package com.pinlog.pinlogback.domain.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FastAPI AI Server 연동 설정(AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 2.1).
 *
 * <p>base-url과 시크릿을 코드 상수로 두지 않는 이유는 배포 환경마다 다르기 때문이고, 타임아웃을
 * 설정으로 둔 이유는 튜닝 대상이기 때문이다. 값의 정본은 명세이며 여기서 임의로 바꾸지 않는다.
 *
 * <p>검색(`/internal/v1/search`)용 키(`search`·`embedding-profile`)는 아직 두지 않는다. 명세에는
 * 있지만 소비자가 생기는 티켓(S15P11A705-135)에서 함께 들어와야 "설정은 있는데 아무도 안 읽는"
 * 구간이 생기지 않는다.
 *
 * @param baseUrl FastAPI base URL
 * @param internalSecret 서비스 간 공유 시크릿. {@code X-Internal-Secret} 헤더로 실린다
 * @param process {@code POST /internal/v1/context/process} 타임아웃
 */
@ConfigurationProperties("pinlog.ai")
public record AiProperties(
	String baseUrl,
	String internalSecret,
	Timeouts process
) {

	/**
	 * @param connectTimeout 연결 수립 상한
	 * @param readTimeout 응답 대기 상한. {@code process}는 202 접수 확인만 받으므로 짧다
	 */
	public record Timeouts(Duration connectTimeout, Duration readTimeout) {
	}
}
