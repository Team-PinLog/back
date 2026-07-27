package com.pinlog.pinlogback.global.response;

import java.util.List;

/**
 * 커서 기반 목록 응답의 공용 형태 {@code {items, nextCursor, hasNext}}(공개 API 명세 1.4).
 *
 * <p>{@code @JsonInclude}를 붙이지 않는다. 마지막 페이지에서 {@code nextCursor}는 명세가 보여주는
 * {@code "opaque-cursor-or-null"}대로 명시적인 {@code null}로 직렬화되어야 한다. {@link ApiResponse}처럼
 * {@code NON_NULL}로 생략하면 클라이언트가 "키 부재"와 "null"을 구분해야 하는 부담이 생긴다.
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {

	/** 명세 1.4가 정한 기본 페이지 크기. */
	public static final int DEFAULT_SIZE = 20;

	/** 명세는 상한을 두지 않지만, 서버 내부 방어 상한으로 대량 조회에 의한 부하를 막기 위해 100으로 둔다. */
	public static final int MAX_SIZE = 100;

	public CursorPage {
		items = List.copyOf(items);
	}

	public static <T> CursorPage<T> of(List<T> items, String nextCursor) {
		return new CursorPage<>(items, nextCursor, nextCursor != null);
	}

	public static <T> CursorPage<T> last(List<T> items) {
		return new CursorPage<>(items, null, false);
	}

	public static <T> CursorPage<T> empty() {
		return new CursorPage<>(List.of(), null, false);
	}

	public static int normalizeSize(Integer requested) {
		if (requested == null || requested <= 0) {
			return DEFAULT_SIZE;
		}
		if (requested > MAX_SIZE) {
			return MAX_SIZE;
		}
		return requested;
	}
}
