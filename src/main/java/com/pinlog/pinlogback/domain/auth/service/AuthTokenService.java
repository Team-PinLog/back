package com.pinlog.pinlogback.domain.auth.service;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.global.exception.UnauthorizedException;
import com.pinlog.pinlogback.global.security.token.JwtProperties;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider.IssuedRefreshToken;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider.RefreshTokenClaims;

import lombok.extern.slf4j.Slf4j;

/**
 * 세션 토큰의 발급·회전·폐기(API 명세 3.2~3.4).
 *
 * @see RefreshTokenStore 회전 상태를 들고 있는 곳
 */
@Slf4j
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
	 * <p>이미 회전된 토큰이 다시 들어오면 유출 신호로 보고 <b>그 회원의 모든 세션을 폐기한다</b>
	 * (RFC 9700 §4.14.2, BD-35). 401만 돌려주면 유출 시 정상 사용자만 끊기고 공격자가 방금
	 * 회전해 받은 토큰은 살아남는다.
	 *
	 * <p>대가는 오탐이다 — 같은 토큰을 두 번 보내기만 해도 전체 로그아웃이 된다. 서버는 재사용과
	 * 유출을 구별할 수 없어 안전한 쪽으로 판단한다. 방어선은 공용 계약이 정한 "재발급 요청은
	 * 동시에 하나만"이다(08 §3.3).
	 *
	 * @param refreshToken 쿠키에서 온 값. 쿠키가 없으면 {@code null}이다
	 * @throws UnauthorizedException 쿠키가 없거나, 서명·만료·용도가 맞지 않거나,
	 *                               이미 회전·폐기된 토큰일 때
	 */
	public TokenPair rotate(@Nullable String refreshToken) {
		RefreshTokenClaims claims = parse(refreshToken).orElseThrow(UnauthorizedException::new);
		if (!refreshTokenStore.consume(claims.memberId(), claims.tokenId())) {
			// 서명·만료는 통과했는데 이미 소비된 토큰이다. 정상 흐름에서는 나오지 않는다 —
			// 유출됐거나 클라이언트가 같은 토큰을 두 번 보냈다는 뜻이다.
			log.warn("refresh token reuse detected, revoking all sessions: memberId={}", claims.memberId());
			refreshTokenStore.revokeAll(claims.memberId());
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
