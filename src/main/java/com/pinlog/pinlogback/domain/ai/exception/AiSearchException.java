package com.pinlog.pinlogback.domain.ai.exception;

import com.pinlog.pinlogback.global.exception.BusinessException;
import com.pinlog.pinlogback.global.exception.ErrorCode;

/**
 * 자연어 검색을 위한 FastAPI 호출이 실패했다.
 *
 * <p><b>{@code process}와 실패 정책이 정반대인 것이 이 타입의 존재 이유다.</b>
 * {@link com.pinlog.pinlogback.domain.ai.client.AiProcessClient}는 모든 실패를 삼킨다 — 접수만
 * 하는 fire-and-forget이고 {@code PENDING}이 남아 재스캔이 줍기 때문이다. 검색에는 그런 뒷수습이
 * 없다. 삼키면 사용자에게 <b>빈 결과</b>가 보이고, 빈 결과는 "일치하는 기록이 없음"과 구분되지
 * 않아 장애와 설정 오류를 그대로 감춘다(ai 레포 {@code docs/spec/model-profile.md} 3.1).
 *
 * <p>그래서 이 예외는 밖으로 나간다. 다만 <b>검색 경로에서만</b> 나간다 — 저장·조회·발행은 이
 * 호출을 타지 않으므로 FastAPI가 죽어도 그대로 동작한다(AI 설계 응답 조립 6.4).
 */
public class AiSearchException extends BusinessException {

	private AiSearchException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}

	/**
	 * Spring이 보낸 Profile과 FastAPI 설정의 Profile이 다르다(422).
	 *
	 * <p>재시도해도 풀리지 않는다. 배포 설정이 어긋난 상태이며 사람이 고쳐야 한다. 양쪽 값은
	 * 호출자가 로그에 남기고 여기서는 응답에 싣지 않는다 — 내부 설정값은 응답에 노출하지 않는다.
	 */
	public static AiSearchException profileMismatch() {
		return new AiSearchException(ErrorCode.SEARCH_PROFILE_MISMATCH,
			ErrorCode.SEARCH_PROFILE_MISMATCH.getMessage());
	}

	/** 연결 실패·타임아웃·5xx, 그리고 Profile 불일치가 아닌 나머지 4xx. */
	public static AiSearchException unavailable() {
		return new AiSearchException(ErrorCode.SEARCH_UNAVAILABLE,
			ErrorCode.SEARCH_UNAVAILABLE.getMessage());
	}
}
