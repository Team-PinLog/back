package com.pinlog.pinlogback.global.security.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@DisplayName("세션 JWT 발급·검증")
class JwtTokenProviderTest {

	private static final String ISSUER = "pinlog";

	private final JwtProperties properties =
		new JwtProperties(null, Duration.ofMinutes(30), Duration.ofDays(7), ISSUER);
	private final JwtKeyProvider keyProvider = new JwtKeyProvider(properties, new MockEnvironment());
	private final JwtTokenProvider tokenProvider = new JwtTokenProvider(properties, keyProvider);

	@Test
	@DisplayName("발급한 Access 토큰에서 회원 식별자를 되읽는다")
	void accessTokenRoundTrip() {
		String token = tokenProvider.issueAccessToken(42L);

		assertThat(tokenProvider.parseAccessToken(token)).contains(42L);
	}

	@Test
	@DisplayName("Access 토큰은 RS256으로 서명되고 kid를 담는다")
	void accessTokenCarriesAlgorithmAndKeyId() throws Exception {
		JWSHeader header = SignedJWT.parse(tokenProvider.issueAccessToken(1L)).getHeader();

		assertThat(header.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
		// kid가 없으면 나중에 키를 회전할 때 신·구 키를 구분할 수 없어 전면 로그아웃이 강제된다(BD-31).
		assertThat(header.getKeyID()).isNotBlank();
	}

	@Test
	@DisplayName("kid는 키가 같으면 같은 값이다")
	void keyIdIsDerivedFromTheKeyNotRandom() {
		// 난수면 같은 키를 쓰는 파드끼리 kid가 달라져 회전 시 값이 없어진다(BD-31).
		JwtKeyProvider sameKeyAgain = new JwtKeyProvider(properties, new MockEnvironment());

		assertThat(keyProvider.rsaKey().getKeyID()).isEqualTo(keyProvider.rsaKey().getKeyID());
		assertThat(sameKeyAgain.rsaKey().getKeyID()).isNotEqualTo(keyProvider.rsaKey().getKeyID());
	}

	@Test
	@DisplayName("공개키를 HMAC 비밀로 삼아 HS256으로 서명한 토큰은 거부된다")
	void hmacSignedTokenWithPublicKeyAsSecretIsRejected() throws Exception {
		// alg confusion — 검증이 토큰 헤더의 alg를 따라가면 통과한다. RFC 8725 §3.1이 막으라는 것이
		// 정확히 이 경로이고, JWSVerificationKeySelector에 RS256만 넘겨 고정한 이유다.
		byte[] publicKeyAsSecret = keyProvider.rsaKey().toPublicJWK().toJSONString()
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
			.subject("1")
			.issuer(ISSUER)
			.expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000))
			.jwtID("forged")
			.claim("token_use", "access")
			.build();
		SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		forged.sign(new MACSigner(publicKeyAsSecret));

		assertThat(tokenProvider.parseAccessToken(forged.serialize())).isEmpty();
	}

	@Test
	@DisplayName("Refresh 토큰은 Access 자리에서 거부된다")
	void refreshTokenIsNotAcceptedAsAccessToken() {
		// 용도 구분이 없으면 7일짜리가 30분짜리 자리를 대신한다.
		String refreshToken = tokenProvider.issueRefreshToken(1L).token();

		assertThat(tokenProvider.parseAccessToken(refreshToken)).isEmpty();
	}

	@Test
	@DisplayName("Access 토큰은 Refresh 자리에서 거부된다")
	void accessTokenIsNotAcceptedAsRefreshToken() {
		String accessToken = tokenProvider.issueAccessToken(1L);

		assertThat(tokenProvider.parseRefreshToken(accessToken)).isEmpty();
	}

	@Test
	@DisplayName("다른 키로 서명한 토큰은 거부된다")
	void tokenSignedByAnotherKeyIsRejected() {
		JwtTokenProvider other = new JwtTokenProvider(
			properties, new JwtKeyProvider(properties, new MockEnvironment()));

		assertThat(tokenProvider.parseAccessToken(other.issueAccessToken(1L))).isEmpty();
	}

	@Test
	@DisplayName("만료된 토큰은 거부된다")
	void expiredTokenIsRejected() {
		// nimbus의 DefaultJWTClaimsVerifier는 기본 60초의 시계 오차를 허용한다. 분산 환경에서
		// 필요한 관용이라 그대로 두되, 그만큼 실제 만료가 늦다는 뜻이므로 여유를 두고 검증한다.
		JwtProperties expiring =
			new JwtProperties(null, Duration.ofMinutes(-5), Duration.ofDays(7), ISSUER);
		JwtTokenProvider provider = new JwtTokenProvider(expiring, keyProvider);

		assertThat(tokenProvider.parseAccessToken(provider.issueAccessToken(1L))).isEmpty();
	}

	@Test
	@DisplayName("만료 직후 60초는 시계 오차로 허용된다")
	void expiryToleratesClockSkew() {
		// 위 테스트가 왜 -5분인지를 고정한다. 이 관용을 없애려면 명시적으로 줄여야 한다.
		JwtProperties justExpired =
			new JwtProperties(null, Duration.ofSeconds(-5), Duration.ofDays(7), ISSUER);
		JwtTokenProvider provider = new JwtTokenProvider(justExpired, keyProvider);

		assertThat(tokenProvider.parseAccessToken(provider.issueAccessToken(1L))).contains(1L);
	}

	@Test
	@DisplayName("발급자가 다른 토큰은 거부된다")
	void tokenFromAnotherIssuerIsRejected() {
		JwtProperties otherIssuer =
			new JwtProperties(null, Duration.ofMinutes(30), Duration.ofDays(7), "someone-else");
		JwtTokenProvider provider = new JwtTokenProvider(otherIssuer, keyProvider);

		assertThat(tokenProvider.parseAccessToken(provider.issueAccessToken(1L))).isEmpty();
	}

	@Test
	@DisplayName("Refresh 토큰마다 jti가 다르다")
	void everyRefreshTokenHasItsOwnId() {
		// 회전 추적이 jti로 이뤄지므로 겹치면 한 기기의 재발급이 다른 기기를 끊는다.
		String first = tokenProvider.issueRefreshToken(1L).tokenId();
		String second = tokenProvider.issueRefreshToken(1L).tokenId();

		assertThat(first).isNotEqualTo(second);
	}
}
