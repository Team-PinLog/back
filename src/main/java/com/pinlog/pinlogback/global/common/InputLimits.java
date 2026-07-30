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

	/**
	 * 자연어 검색 질의 길이. Context 본문과 <b>같은 값</b>이며 이유도 같다 — 질의도 그대로 임베딩
	 * 입력이 되어 호출 비용과 직결된다(AI 설계 9.2: 질의는 분해하지 않고 전체를 한 번 임베딩한다).
	 *
	 * <p><b>API 명세 1.9의 상한 표에는 이 항목이 없다.</b> 명세가 금지한 것이 아니라 아직 다루지 않은
	 * 자리이며, 상한이 아예 없으면 임의 길이의 문자열이 곧바로 외부 임베딩 호출 비용이 되므로
	 * 백엔드 방어로 둔다. FastAPI 쪽 스키마도 {@code min_length}만 있고 상한이 없다
	 * (ai 레포 {@code app/schema/search.py}). 명세에 반영되면 그 값이 정본이다.
	 */
	public static final int SEARCH_QUERY_MAX = CONTEXT_BODY_MAX;

	/**
	 * 한 요청이 담을 수 있는 Feed 이벤트 수(API 명세 10.2). {@code RECORD_IDS_MAX}·
	 * {@code CursorPage.MAX_SIZE}와 <b>같은 값</b>으로 둔다 — 요청 배열마다 상한을 따로 정하면
	 * "서버 방어 상한이 얼마인가"에 답이 여러 개가 된다(S15P11A705-117 규약).
	 *
	 * <p>초과 요청은 초과분만 잘라내지 않고 {@code 400 INVALID_INPUT}으로 거절한다. 잘라내면
	 * 관측 이벤트가 조용히 유실되고 클라이언트가 그것을 알 방법이 없다.
	 */
	public static final int FEED_EVENTS_MAX = 100;

	private InputLimits() {
	}
}
