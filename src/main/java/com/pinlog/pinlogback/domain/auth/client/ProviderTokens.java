package com.pinlog.pinlogback.domain.auth.client;

import org.jspecify.annotations.Nullable;

/**
 * 탈퇴 왕복이 방금 받아 온 공급자 토큰. <b>보관하지 않는다</b> — 콜백 한 요청 안에서 해제 호출로
 * 나가고 사라진다(BD-48 §①).
 *
 * <p>두 값을 함께 나르는 이유는 <b>공급자마다 승인을 지우는 토큰이 다르기 때문</b>이다. Kakao와
 * Naver는 연결 단위 API라 access token으로 끊기지만, Google은 토큰 단위라 access token을 폐기해도
 * 승인이 남는다 — 그쪽은 refresh token을 폐기해야 한다.
 *
 * @param accessToken 항상 있다
 * @param refreshToken 공급자와 인가 요청에 따라 없을 수 있다. Google 탈퇴 왕복만
 *     {@code access_type=offline}·{@code prompt=consent}로 이 값을 요구한다
 */
public record ProviderTokens(String accessToken, @Nullable String refreshToken) {

	/** refresh token을 요구하지 않는 공급자용. */
	public static ProviderTokens of(String accessToken) {
		return new ProviderTokens(accessToken, null);
	}
}
