package com.pinlog.pinlogback.domain.ai.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재스캔·Finalizer 파라미터(AI 파트 소유 명세 {@code docs/ai/spec/ai-rescan-scheduler.md} 2장).
 *
 * <p><b>모두 설정값이다.</b> 상수로 박으면 만료 기준을 바꿀 때 재배포가 필요하고, 무엇보다
 * "만료됐다"를 테스트에서 만들 수 없다 — 5분을 기다리는 테스트는 쓸 수 없다. 값의 정본은 명세이며
 * 여기서 임의로 바꾸지 않는다.
 *
 * <p>{@code pinlog.ai} 아래에 있지만 {@link com.pinlog.pinlogback.domain.ai.AiProperties}에 합치지
 * 않는다. 저쪽은 <b>FastAPI 연결 계약</b>(주소·시크릿·타임아웃)이고 이쪽은 <b>복구 정책</b>이라
 * 바뀌는 이유가 다르다. 접두어가 겹쳐도 충돌하지 않는다 — {@code @ConfigurationProperties}는 모르는
 * 키를 무시하므로 {@code AiProperties}가 {@code rescan}을 보고 실패하지 않는다.
 *
 * <p>{@code scheduler}가 아니라 여기 있는 이유는 <b>값을 읽는 곳이 여기</b>이기 때문이다
 * (package-structure.md — 설정은 소비자와 같은 패키지에 둔다). {@link AiFailedFinalizer}와
 * {@link AiRescanCandidateService} 둘이 소비자이고, 스케줄러는 주기 하나를 애노테이션 문자열로만
 * 읽는다. 반대로 두면 {@code service}가 {@code scheduler}를 참조해 패키지 의존이 순환한다.
 *
 * @param interval 실행 주기. <b>이 값을 읽는 것은 Java 코드가 아니라
 *     {@code AiRescanScheduler}의 {@code @Scheduled(fixedDelayString)}이다.</b> 여기 둔 이유는 그
 *     애노테이션이 문자열 placeholder만 받아 어떤 값인지 타입으로 드러나지 않기 때문이다
 * @param pendingExpiry {@code PENDING}이 이 시간을 넘기면 유실로 본다
 * @param processingExpiry {@code PROCESSING}이 이 시간을 넘기면 프로세스 종료로 유실된 것으로 본다.
 *     {@code pendingExpiry}보다 긴 이유는 실제로 처리 중일 가능성을 고려하기 때문이다(명세 2장)
 * @param maxRetry 재시도 상한. <b>정본은 DB의 {@code CHECK (retry_count BETWEEN 0 AND 3)}이다</b>
 *     ({@code V100__ai_tables.sql}) — 이 값을 올리면 증가 UPDATE가 제약 위반으로 실패한다
 * @param batchSize 한 회차에 집는 후보 수 상한. 재스캔과 Finalizer가 각각 이 값을 쓴다
 */
@ConfigurationProperties("pinlog.ai.rescan")
public record AiRescanProperties(
	Duration interval,
	Duration pendingExpiry,
	Duration processingExpiry,
	int maxRetry,
	int batchSize
) {
}
