package com.pinlog.pinlogback.global.response;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 반환된 지점 전체를 포함하는 최소 사각형(API 명세 4.2). 지점이 1개면 sw와 ne가 같은 점 사각형이고,
 * 하나도 없으면 응답에 명시적 {@code null}로 실린다 — 프론트의 {@code fitBounds} 분기 기준이다.
 *
 * <p>{@code domain/record}에 있던 것을 여기로 올렸다. 자연어 검색 응답이 <b>같은 규칙으로</b> 같은
 * 형태를 내려주므로(API 명세 6.1 "4.2와 동일 규칙") 두 도메인이 공유하게 됐고, 그러면 어느 한
 * 도메인에 두지 않는다는 패키지 규약을 따른 것이다. 도메인마다 복제하면 "같은 규칙"이 문서에만
 * 남고 코드에서는 갈라진다.
 */
public record BoundsResponse(BigDecimal swLat, BigDecimal swLng, BigDecimal neLat, BigDecimal neLng) {

	/**
	 * 계산까지 여기 두는 이유는 타입보다 <b>규칙</b>이 공유 대상이기 때문이다. 값만 공유하고 min/max를
	 * 도메인마다 다시 짜면 "결과 없음이 null인가 점 사각형인가" 같은 판단이 조용히 갈라진다.
	 *
	 * @return 항목이 없으면 {@code null}. 빈 사각형(0,0,0,0)을 돌려주지 않는다 — 지도가 아프리카
	 *     서쪽 바다로 이동한다
	 */
	public static <T> BoundsResponse enclosing(List<T> items,
		Function<T, BigDecimal> lat, Function<T, BigDecimal> lng) {
		if (items.isEmpty()) {
			return null;
		}
		return new BoundsResponse(
			extreme(items, lat, Comparator.naturalOrder()),
			extreme(items, lng, Comparator.naturalOrder()),
			extreme(items, lat, Comparator.reverseOrder()),
			extreme(items, lng, Comparator.reverseOrder()));
	}

	private static <T> BigDecimal extreme(List<T> items,
		Function<T, BigDecimal> value, Comparator<BigDecimal> order) {
		return items.stream().map(value).min(order).orElseThrow();
	}
}
