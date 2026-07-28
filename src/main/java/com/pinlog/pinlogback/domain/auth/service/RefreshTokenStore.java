package com.pinlog.pinlogback.domain.auth.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 유효한 Refresh 토큰의 {@code jti}를 Redis에 들고 있는다(BD-21).
 *
 * <p>JWT는 스스로 폐기되지 않으므로 "아직 회전되지 않았다"는 사실을 서버가 따로 알아야 한다.
 * 발급한 {@code jti}마다 키를 하나 두고, 재발급할 때 <b>삭제로 소비</b>한다.
 *
 * <p>회원당 하나가 아니라 {@code jti}당 하나인 이유: 회원당 한 개면 두 기기에 로그인했을 때
 * 한쪽 재발급이 다른 쪽을 끊는다. {@code jti}별로 두면 세션이 서로 독립적이다.
 *
 * <p>{@code delete}의 반환값이 곧 검사 결과다. 조회 후 삭제로 나누면 두 요청이 같은 토큰을
 * 동시에 소비할 수 있다 — 회전 전 재사용을 잡아내려는 목적과 어긋난다.
 */
@Component
public class RefreshTokenStore {

	private static final String KEY_PREFIX = "auth:refresh:";

	private final StringRedisTemplate redisTemplate;

	public RefreshTokenStore(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void save(Long memberId, String tokenId, Duration ttl) {
		redisTemplate.opsForValue().set(key(memberId, tokenId), "1", ttl);
	}

	/**
	 * 토큰을 소비한다. 이후 같은 {@code jti}로는 실패한다.
	 *
	 * @return 유효한 토큰이었으면 {@code true}. 이미 회전됐거나 로그아웃됐으면 {@code false}
	 */
	public boolean consume(Long memberId, String tokenId) {
		return Boolean.TRUE.equals(redisTemplate.delete(key(memberId, tokenId)));
	}

	private String key(Long memberId, String tokenId) {
		return KEY_PREFIX + memberId + ":" + tokenId;
	}
}
