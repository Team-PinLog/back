package com.pinlog.pinlogback.domain.auth.service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

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
 *
 * <p>회원별 인덱스({@code auth:refresh-index:&lt;memberId&gt;} Set)를 함께 둔다. 위 구조는 세션을
 * 독립시키는 대신 <b>회원 단위로 모아 볼 수단을 없애는데</b>, {@link #revokeAll}이 그 목록을
 * 필요로 한다. {@code SCAN}으로 대신하지 않는 이유는 BD-35에 있다.
 */
@Component
public class RefreshTokenStore {

	private static final String TOKEN_KEY_PREFIX = "auth:refresh:";
	private static final String INDEX_KEY_PREFIX = "auth:refresh-index:";

	private final StringRedisTemplate redisTemplate;

	public RefreshTokenStore(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * 새 {@code jti}를 유효 목록과 회원별 인덱스에 넣는다.
	 *
	 * <p>인덱스 수명을 발급마다 다시 건다. {@code SADD}는 TTL을 만들지 않으므로, 빼면 인덱스가
	 * 만료 없는 키로 남는다.
	 */
	public void save(Long memberId, String tokenId, Duration ttl) {
		String indexKey = indexKey(memberId);
		redisTemplate.opsForValue().set(tokenKey(memberId, tokenId), "1", ttl);
		redisTemplate.opsForSet().add(indexKey, tokenId);
		redisTemplate.expire(indexKey, ttl);
	}

	/**
	 * 토큰을 소비하고 회원별 인덱스에서도 뺀다. 이후 같은 {@code jti}로는 실패한다.
	 *
	 * <p>인덱스에서 빼는 것은 정리일 뿐 폐기의 정확성과 무관하다 — 남아 있어도
	 * {@link #revokeAll}은 없는 키를 지우고 넘어간다.
	 *
	 * @return 유효한 토큰이었으면 {@code true}. 이미 회전됐거나 로그아웃됐으면 {@code false}
	 */
	public boolean consume(Long memberId, String tokenId) {
		boolean consumed = Boolean.TRUE.equals(redisTemplate.delete(tokenKey(memberId, tokenId)));
		redisTemplate.opsForSet().remove(indexKey(memberId), tokenId);
		return consumed;
	}

	/**
	 * 이 회원의 Refresh를 <b>전부</b> 폐기한다. 어느 기기에서도 재발급이 불가능해진다.
	 *
	 * <p>재사용 감지(유출 신호)와 회원 탈퇴가 같은 연산을 필요로 한다. 멱등하므로 두 요청이
	 * 동시에 들어와도 문제가 없다.
	 */
	public void revokeAll(Long memberId) {
		String indexKey = indexKey(memberId);
		Set<String> tokenIds = redisTemplate.opsForSet().members(indexKey);
		if (tokenIds != null && !tokenIds.isEmpty()) {
			List<String> tokenKeys = tokenIds.stream().map(tokenId -> tokenKey(memberId, tokenId)).toList();
			redisTemplate.delete(tokenKeys);
		}
		redisTemplate.delete(indexKey);
	}

	private String tokenKey(Long memberId, String tokenId) {
		return TOKEN_KEY_PREFIX + memberId + ":" + tokenId;
	}

	private String indexKey(Long memberId) {
		return INDEX_KEY_PREFIX + memberId;
	}
}
