package com.pinlog.pinlogback.domain.search.repository;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Core 재검증을 통과한 Record 한 건(AI 파트 소유 명세 {@code docs/ai/spec/ai-response-assembly.md} 6.1).
 *
 * <p><b>이 타입의 존재 자체가 검증의 결과다.</b> 여기 담긴 Record는 요청자 소유이고, 삭제되지 않았고,
 * 활성 Context가 하나 이상 있고, Place 조인에 성공한 것뿐이다. FastAPI가 준 원본 id 목록과 이 목록의
 * 차집합이 곧 <b>조용히 제외된 것들</b>이다(명세 6.3).
 */
public record VerifiedSearchRecord(
	Long recordId,
	Instant createdAt,
	Long placeId,
	String placeName,
	String placeAddress,
	BigDecimal lat,
	BigDecimal lng
) {
}
