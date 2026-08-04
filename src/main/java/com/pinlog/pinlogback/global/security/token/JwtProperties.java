package com.pinlog.pinlogback.global.security.token;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 세션 JWT 설정(BD-31). 수명 값의 원본은 BD-21이다.
 *
 * @param privateKey RS256 개인키(PKCS#8 PEM). 비어 있으면 로컬·테스트는 임시 키쌍을 만들고
 *                   운영 프로파일은 기동에 실패한다 — 파드마다 다른 키가 생기면 스케일아웃 때
 *                   전면 로그아웃이 되므로, 조용히 망가지는 것보다 뜨지 않는 편이 낫다.
 * @param accessTokenTtl Access 쿠키 수명
 * @param refreshTokenTtl Refresh 쿠키 수명이자 Redis 저장 TTL
 * @param withdrawalTicketTtl 탈퇴 인가 왕복을 시작할 권한의 수명(BD-48). 사용자가 공급자 화면을
 *                            거치는 동안만 유효하면 되므로 짧다
 * @param issuer 발급자 클레임. 검증에서 이 값을 요구한다
 */
@ConfigurationProperties("pinlog.auth.jwt")
public record JwtProperties(
	@Nullable String privateKey,
	Duration accessTokenTtl,
	Duration refreshTokenTtl,
	Duration withdrawalTicketTtl,
	String issuer
) {
}
