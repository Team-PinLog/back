package com.pinlog.pinlogback.domain.auth.service;

import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.global.config.JwtProperties;
import com.pinlog.pinlogback.global.exception.UnauthorizedException;
import com.pinlog.pinlogback.global.security.JwtTokenProvider;
import com.pinlog.pinlogback.global.security.JwtTokenProvider.IssuedRefreshToken;
import com.pinlog.pinlogback.global.security.JwtTokenProvider.RefreshTokenClaims;

/**
 * 세션 토큰의 발급·회전·폐기(API 명세 3.2~3.4).
 *
 * @see RefreshTokenStore 회전 상태를 들고 있는 곳
 */
@Service
public class AuthTokenService {

	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final JwtProperties properties;

	public AuthTokenService(
		JwtTokenProvider tokenProvider,
		RefreshTokenStore refreshTokenStore,
		JwtProperties properties
	) {
		this.tokenProvider = tokenProvider;
		this.refreshTokenStore = refreshTokenStore;
		this.properties = properties;
	}

	/** 로그인 성공 직후 새 세션을 연다. */
	public TokenPair issue(Long memberId) {
		IssuedRefreshToken refreshToken = tokenProvider.issueRefreshToken(memberId);
		refreshTokenStore.save(memberId, refreshToken.tokenId(), properties.refreshTokenTtl());
		return new TokenPair(tokenProvider.issueAccessToken(memberId), refreshToken.token());
	}

	/**
	 * Refresh를 회전한다. 이전 토큰은 이 시점에 무효가 된다.
	 *
	 * @throws UnauthorizedException 서명·만료·용도가 맞지 않거나, 이미 회전·폐기된 토큰일 때
	 */
	public TokenPair rotate(String refreshToken) {
		RefreshTokenClaims claims = tokenProvider.parseRefreshToken(refreshToken)
			.orElseThrow(UnauthorizedException::new);
		if (!refreshTokenStore.consume(claims.memberId(), claims.tokenId())) {
			throw new UnauthorizedException();
		}
		return issue(claims.memberId());
	}

	/**
	 * 세션을 닫는다. 토큰이 이미 무효여도 실패로 보지 않는다 — 로그아웃은 멱등해야 하고,
	 * 여기서 401을 내면 클라이언트가 쿠키를 지우지 못한 채 남는다.
	 */
	public void logout(String refreshToken) {
		tokenProvider.parseRefreshToken(refreshToken)
			.ifPresent(claims -> refreshTokenStore.consume(claims.memberId(), claims.tokenId()));
	}

	public record TokenPair(String accessToken, String refreshToken) {
	}
}
