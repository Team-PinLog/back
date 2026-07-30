package com.pinlog.pinlogback.domain.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FastAPI AI Server 연동 설정(AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 2.1).
 *
 * <p>base-url과 시크릿을 코드 상수로 두지 않는 이유는 배포 환경마다 다르기 때문이고, 타임아웃을
 * 설정으로 둔 이유는 튜닝 대상이기 때문이다. 값의 정본은 명세이며 여기서 임의로 바꾸지 않는다.
 *
 * @param baseUrl FastAPI base URL
 * @param internalSecret 서비스 간 공유 시크릿. {@code X-Internal-Secret} 헤더로 실린다
 * @param embeddingProfile 검색 요청에 실어 보내는 Embedding Profile. Spring은 이 값을 해석하지 않고
 *     그대로 전달하며, FastAPI가 자기 설정과 대조한다(공용 계약 05 §7.1). 취득 경로의 근거는
 *     {@code docs/backend/decisions/BD-39-embedding-profile-in-application-config.md}
 * @param process {@code POST /internal/v1/context/process} 타임아웃
 * @param search {@code POST /internal/v1/search} 타임아웃
 */
@ConfigurationProperties("pinlog.ai")
public record AiProperties(
	String baseUrl,
	String internalSecret,
	String embeddingProfile,
	Timeouts process,
	Timeouts search
) {

	/**
	 * @param connectTimeout 연결 수립 상한
	 * @param readTimeout 응답 대기 상한. {@code process}는 202 접수 확인만 받으므로 짧고,
	 *     {@code search}는 질의 임베딩 생성과 벡터 검색이 응답 경로에 있어 더 길다
	 */
	public record Timeouts(Duration connectTimeout, Duration readTimeout) {
	}
}
