package com.pinlog.pinlogback.domain.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결합 신뢰도 게이트(S15P11A705-400, {@code OFFTOPIC-CONFIDENCE-GATE-HANDOFF-DRAFT.md} §4) 설정.
 *
 * <p>{@code enabled}의 기본값이 {@code false}인 것이 이 트랙의 다른 신호(문자열 병합·키워드
 * 재정렬)와 같은 안전장치다 — 새 신호는 끈 상태가 현행과 동일해야 하고, 켜는 것은 검증 게이트
 * 통과 뒤의 결정이다.
 *
 * @param enabled 게이트를 켤지. 꺼져 있으면 어떤 결과도 신뢰도만으로 제외되지 않는다
 * @param similarityThreshold S1(벡터) 신호 하나뿐인 결과를 제외하는 유사도 하한. 오프라인
 *     재측정(ai 레포 {@code docs/implements/2026-08-07-gate-threshold-remeasure.md},
 *     S15P11A705-401)이 0.35를 채택했다 — 다른 검색 신호(키워드 재정렬 floor)에서 값만 재사용한
 *     것이 아니라 이 용도로 별도로 다시 쟀다
 */
@ConfigurationProperties("pinlog.search.gate")
public record ConfidenceGateProperties(
	boolean enabled,
	double similarityThreshold
) {
}
