package com.pinlog.pinlogback.domain.auth.service;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.global.config.JwtProperties;
import com.pinlog.pinlogback.global.exception.UnauthorizedException;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider.IssuedRefreshToken;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider.RefreshTokenClaims;

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
	 * @param refreshToken 쿠키에서 온 값. 쿠키가 없으면 {@code null}이다
	 * @throws UnauthorizedException 쿠키가 없거나, 서명·만료·용도가 맞지 않거나,
	 *                               이미 회전·폐기된 토큰일 때
	 */
	public TokenPair rotate(@Nullable String refreshToken) {
		RefreshTokenClaims claims = parse(refreshToken).orElseThrow(UnauthorizedException::new);
		if (!refreshTokenStore.consume(claims.memberId(), claims.tokenId())) {
			throw new UnauthorizedException();
		}
		return issue(claims.memberId());
	}

	/**
	 * 세션을 닫는다. 쿠키가 없거나 토큰이 이미 무효여도 실패로 보지 않는다 — 로그아웃은
	 * 멱등해야 하고, 여기서 401을 내면 클라이언트가 쿠키를 지우지 못한 채 남는다.
	 */
	public void logout(@Nullable String refreshToken) {
		parse(refreshToken)
			.ifPresent(claims -> refreshTokenStore.consume(claims.memberId(), claims.tokenId()));
	}

	/** 쿠키 부재와 검증 실패를 같은 결과로 묶는다. 둘을 가르는 의미가 호출자에게 없다. */
	private Optional<RefreshTokenClaims> parse(@Nullable String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			return Optional.empty();
		}
		return tokenProvider.parseRefreshToken(refreshToken);
	}

	public record TokenPair(String accessToken, String refreshToken) {
	}
}
