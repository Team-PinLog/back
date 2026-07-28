package com.pinlog.pinlogback.global.common;

/**
 * 요청 입력의 서버 방어 상한(S15P11A705-117). 값이 DTO마다 흩어지면 한 곳만 바뀌어 엔드포인트별로
 * 상한이 달라지므로 여기 모은다 — 같은 본문이 세 경로로 들어오는 Context가 특히 그렇다.
 *
 * <p>{@code @Size(max = ...)}에 쓰려면 컴파일 타임 상수여야 하므로 {@code static final int}로 둔다.
 */
public final class InputLimits {

	/**
	 * 한 요청이 담을 수 있는 Record 수. 이 배열은 Collection 행을 잠근 상태에서 건당 INSERT를
	 * 돌기 때문에, 상한이 없으면 큰 배열 하나가 그 Collection의 다른 요청을 오래 막는다.
	 *
	 * <p>목록 조회의 방어 상한({@code CursorPage.MAX_SIZE})과 같은 값으로 정했다 — 두 기준이 다르면
	 * "서버 방어 상한이 얼마인가"에 답이 둘이 된다. 넘겨 담아야 하면 나눠 호출하며, Record 추가는
	 * 멱등이라(API 명세 7.5) 나눠 호출해도 중복이 문제되지 않는다.
	 */
	public static final int RECORD_IDS_MAX = 100;

	/**
	 * Context 본문 길이. 그대로 임베딩 입력이 되어 호출 비용과 직결된다(데이터모델 8장의 미확정
	 * 항목이었다). 임베딩 모델({@code text-embedding-3-small})은 8191토큰까지 받으므로 모델 제약이
	 * 아니라 비용과 UX 기준으로 정한 값이다.
	 *
	 * <p>프론트가 입력 UI에서 같은 값으로 막아야 한다 — 서버만 막으면 사용자가 긴 글을 다 쓴 뒤에
	 * 거절당한다(파트 간 요구사항).
	 */
	public static final int CONTEXT_BODY_MAX = 500;

	private InputLimits() {
	}
}
