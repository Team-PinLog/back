package com.pinlog.pinlogback.domain.ai.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Context→AI 요청을 나르는 큐 설정(BD-48).
 *
 * <p>재시도 값이 상수가 아니라 설정인 이유는 재스캔({@code pinlog.ai.rescan})과 같다 — 체인 소진을
 * 테스트에서 만들려면 시도 횟수와 백오프를 줄일 수 있어야 한다. {@code @RetryableTopic}은 이 record가
 * 아니라 같은 키의 프로퍼티 플레이스홀더를 직접 읽는다(애노테이션 속성이라 Bean 주입이 안 된다).
 * 이 record는 토픽 Bean 조립과 "이 키들이 바인딩 가능한 형태로 존재한다"는 기동 시 검증을 맡는다.
 *
 * @param topic 본 토픽 이름. 재시도 토픽({@code -retry-N})과 DLT({@code -dlt})는 여기서 파생된다
 * @param group 컨슈머 그룹. back 인스턴스들이 하나의 그룹으로 작업을 나눠 갖는다
 * @param retryAttempts 총 시도 횟수(본 토픽 1회 포함). 소진되면 DLT로 격리된다
 * @param retryInitialDelayMs 첫 재시도까지의 지연(밀리초)
 * @param retryMultiplier 재시도마다 지연에 곱하는 배수
 */
@ConfigurationProperties("pinlog.ai.queue")
public record AiQueueProperties(
	String topic,
	String group,
	int retryAttempts,
	long retryInitialDelayMs,
	double retryMultiplier
) {
}
