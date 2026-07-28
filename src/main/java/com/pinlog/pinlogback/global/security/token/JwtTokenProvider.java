package com.pinlog.pinlogback.global.security.token;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * 세션 JWT를 발급하고 검증한다(BD-21·BD-29).
 *
 * <p><b>검증 알고리즘을 고정한다.</b> {@link JWSVerificationKeySelector}에 RS256만 넘기므로
 * 토큰 헤더의 {@code alg}가 무엇이든 그 값으로 검증하지 않는다. RFC 8725 §3.1이 요구하는 지점이며,
 * 이게 없으면 공개키를 HMAC 비밀로 쓰는 alg confusion 공격이 열린다.
 *
 * <p><b>Access와 Refresh를 클레임으로 구분한다.</b> 같은 키로 서명하므로 용도 구분이 없으면
 * 수명 30분짜리가 7일짜리 재발급 권한을 갖는다.
 */
@Component
public class JwtTokenProvider {

	/** Access인지 Refresh인지. 검증에서 필수 클레임으로 요구한다. */
	static final String TOKEN_USE = "token_use";
	private static final String ACCESS = "access";
	private static final String REFRESH = "refresh";
	private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.RS256;

	private final JwtProperties properties;
	private final RSASSASigner signer;
	private final JWSHeader header;
	private final ConfigurableJWTProcessor<SecurityContext> processor;

	public JwtTokenProvider(JwtProperties properties, JwtKeyProvider keyProvider) {
		this.properties = properties;
		try {
			this.signer = new RSASSASigner(keyProvider.rsaKey());
		} catch (JOSEException e) {
			throw new IllegalStateException("RSA 개인키로 서명자를 만들지 못했다", e);
		}
		this.header = new JWSHeader.Builder(ALGORITHM).keyID(keyProvider.rsaKey().getKeyID()).build();

		JWKSource<SecurityContext> keySource =
			new ImmutableJWKSet<>(new JWKSet(keyProvider.rsaKey().toPublicJWK()));
		DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
		jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHM, keySource));
		jwtProcessor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
			new JWTClaimsSet.Builder().issuer(properties.issuer()).build(),
			Set.of(JWTClaimNames.SUBJECT, JWTClaimNames.EXPIRATION_TIME, TOKEN_USE)));
		this.processor = jwtProcessor;
	}

	public String issueAccessToken(Long memberId) {
		return sign(memberId, ACCESS, properties.accessTokenTtl().toSeconds(), UUID.randomUUID().toString());
	}

	/** @return 발급한 Refresh 토큰과 그 {@code jti} — 회전 추적이 {@code jti}로 이뤄진다 */
	public IssuedRefreshToken issueRefreshToken(Long memberId) {
		String tokenId = UUID.randomUUID().toString();
		String token = sign(memberId, REFRESH, properties.refreshTokenTtl().toSeconds(), tokenId);
		return new IssuedRefreshToken(token, tokenId);
	}

	/** @return 검증을 통과한 Access 토큰의 회원 식별자. 실패하면 빈 값 */
	public Optional<Long> parseAccessToken(String token) {
		return parse(token, ACCESS).map(VerifiedToken::memberId);
	}

	/** @return 검증을 통과한 Refresh 토큰의 회원 식별자와 {@code jti}. 실패하면 빈 값 */
	public Optional<RefreshTokenClaims> parseRefreshToken(String token) {
		return parse(token, REFRESH)
			.map(verified -> new RefreshTokenClaims(verified.memberId(), verified.tokenId()));
	}

	private String sign(Long memberId, String tokenUse, long ttlSeconds, String tokenId) {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
			.subject(String.valueOf(memberId))
			.issuer(properties.issuer())
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
			.jwtID(tokenId)
			.claim(TOKEN_USE, tokenUse)
			.build();
		SignedJWT jwt = new SignedJWT(header, claims);
		try {
			jwt.sign(signer);
		} catch (JOSEException e) {
			throw new IllegalStateException("JWT 서명에 실패했다", e);
		}
		return jwt.serialize();
	}

	/**
	 * 서명·만료·발급자·필수 클레임을 검증하고 용도까지 맞는지 확인한다. 실패 원인을 호출자에게
	 * 구분해 알리지 않는다 — 만료인지 위조인지 알려 주면 공격자에게 정보를 준다. {@code sub}가
	 * 숫자가 아닌 경우도 여기서 걸러지므로 호출자는 변환을 다시 하지 않는다.
	 */
	private Optional<VerifiedToken> parse(String token, String expectedUse) {
		try {
			JWTClaimsSet claims = processor.process(token, null);
			if (!expectedUse.equals(claims.getStringClaim(TOKEN_USE))) {
				return Optional.empty();
			}
			return Optional.of(new VerifiedToken(Long.parseLong(claims.getSubject()), claims.getJWTID()));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private record VerifiedToken(Long memberId, String tokenId) {
	}

	public record IssuedRefreshToken(String token, String tokenId) {
	}

	public record RefreshTokenClaims(Long memberId, String tokenId) {
	}
}
