package com.pinlog.pinlogback.domain.ai;

/**
 * 응답에 실리는 Keyword 판정 상태(AI 파트 소유 명세 {@code docs/ai/spec/ai-response-assembly.md} 5.1,
 * API 명세 6.1). <b>{@code ai.context_ai_state.keyword_status}를 그대로 내보낸 것이 아니다.</b>
 *
 * <p>내부 값은 다섯이고 여기는 셋이다. 사용자에게 필요한 판단은 <b>「기다리면 오는가」</b> 하나이며
 * {@code PENDING}과 {@code PROCESSING}의 차이는 그 판단을 바꾸지 않는다. 이름을
 * {@code KeywordStatus}로 두지 않은 이유가 이것이다 — 같은 이름이 다른 값 집합을 뜻하면 어느 쪽
 * 다섯 값인지를 매번 확인해야 한다.
 *
 * <p><b>이 타입이 하는 일은 {@code keywords} 배열이 최종인지를 말해 주는 것뿐이다.</b> 배열 자체의
 * 계약은 이 타입이 생기기 전과 같다(명세 5장) — 미완료도 실패도 빈 배열이며 오류가 아니다.
 */
public enum KeywordResponseStatus {

	/** 판정이 끝났다. {@code keywords}가 최종이며, 0건이면 "이 기록엔 키워드가 없다"는 뜻이다. */
	COMPLETED,

	/**
	 * 아직 처리 중이다. 기다리면 채워진다 — <b>"분석 중"이 사실인 유일한 경우다.</b>
	 *
	 * <p>무한히 지속되지 않는다. 재스캔이 만료된 {@code PENDING}·{@code PROCESSING}을
	 * {@code FAILED}로 전이시키므로({@code ai-rescan-scheduler.md}) 화면의 "분석 중"에 상한이 있다.
	 */
	PROCESSING,

	/** 처리가 끝내 실패했다. 기다려도 오지 않으므로 재시도·문의를 유도할 수 있다. */
	FAILED
}
