package com.pinlog.pinlogback.domain.auth.service;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
 *
 * <p><b>발급·회전·폐기는 Lua로 원자적으로 실행한다.</b> 여러 왕복으로 나누면 그 사이에 다른 요청이
 * 끼어들어 "인덱스에 없는 유효 토큰", "TTL 없는 인덱스", "폐기를 넘긴 토큰"이 생기고, 셋 다 폐기를
 * 무력화한다. Redis는 스크립트 하나를 통째로 원자 실행하므로 중간 상태가 관측되지 않는다.
 *
 * <p>키를 {@code ARGV}로 조립하는 곳이 있어(폐기) Redis Cluster 전제와는 맞지 않는다. 지금 운영은
 * 단일 인스턴스이며, 클러스터로 가면 이 스크립트를 먼저 손봐야 한다.
 */
@Component
public class RefreshTokenStore {

	private static final String TOKEN_KEY_PREFIX = "auth:refresh:";
	private static final String INDEX_KEY_PREFIX = "auth:refresh-index:";

	/**
	 * 토큰 키 저장 · 인덱스 추가 · 인덱스 수명 갱신을 한 번에 끝낸다.
	 *
	 * <p>세 왕복으로 나누면 중간 실패가 두 가지를 남긴다. 토큰만 저장되면 <b>인덱스에 없는 유효
	 * 토큰</b>이 되어 폐기가 7일 동안 못 잡고, {@code EXPIRE}가 빠지면 인덱스가 <b>만료 없는
	 * 영구 키</b>로 남는다.
	 *
	 * <p>여기서 막는 것은 경합이 아니라 <b>부분 실패</b>다. 두 발급이 겹치는 것 자체는 서로 다른
	 * {@code jti}라 무해하므로 {@code MULTI}로도 정확하다 — Lua인 것은 {@link #REVOKE_ALL}과
	 * 메커니즘을 하나로 두려는 선택이며, 이쪽만 트랜잭션으로 바꿔도 맞다.
	 */
	private static final RedisScript<Long> SAVE = RedisScript.of("""
		redis.call('SET', KEYS[1], '1', 'EX', ARGV[2])
		redis.call('SADD', KEYS[2], ARGV[1])
		redis.call('EXPIRE', KEYS[2], ARGV[2])
		return 1
		""", Long.class);

	/**
	 * 옛 토큰을 소비하고 새 토큰을 <b>한 번에</b> 저장한다. 회전의 성패가 곧 반환값이다.
	 *
	 * <p>{@link #consume}과 {@link #save}로 나눠 부르면 그 사이에 {@link #revokeAll}이 끼어들 수
	 * 있고, 그러면 <b>폐기가 끝난 뒤에 저장이 성립해</b> 새 토큰이 살아남는다. 재사용 감지는 동시
	 * 회전이 트리거이므로, 이 창은 하필 폐기가 가장 필요한 순간에 열린다 — BD-35가 약속한 "전 세션
	 * 폐기"가 그 순간에만 지켜지지 않는다. Redis가 스크립트를 통째로 실행하므로 이렇게 두면
	 * {@link #REVOKE_ALL}과 서로 끼어들지 못한다.
	 *
	 * <p>옛 키 삭제가 곧 검사다({@link #consume}과 같은 이유). 0이면 이미 회전·폐기된 토큰이므로
	 * <b>아무것도 쓰지 않고</b> 돌아간다 — 실패한 회전이 새 토큰을 남기면 그것이 다음 사이클까지
	 * 살아남는다.
	 */
	private static final RedisScript<Long> ROTATE = RedisScript.of("""
		if redis.call('DEL', KEYS[1]) == 0 then
			return 0
		end
		redis.call('SREM', KEYS[3], ARGV[1])
		redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
		redis.call('SADD', KEYS[3], ARGV[2])
		redis.call('EXPIRE', KEYS[3], ARGV[3])
		return 1
		""", Long.class);

	/**
	 * 인덱스를 읽어 토큰 키를 지우고 인덱스까지 지운다. 폐기한 토큰 수를 돌려준다.
	 *
	 * <p>읽기와 삭제를 나누면 그 사이의 정상 회전 한 건이 <b>영구히 폐기되지 않는 토큰</b>을
	 * 만든다 — 새 {@code jti}는 이미 읽은 목록에 없어 삭제를 피하고, 뒤따르는 인덱스 삭제가
	 * 그 {@code jti}를 인덱스에서도 지워 다음 폐기의 사정권 밖으로 내보낸다. 재사용 감지는
	 * 회전 요청이 트리거이므로 이 창은 유출 시나리오에서 구조적으로 열린다.
	 *
	 * <p><b>{@code MULTI}로는 대체할 수 없다.</b> 트랜잭션 안에서 명령은 큐에 쌓이고 응답은
	 * {@code EXEC} 때 한꺼번에 오므로, {@code SMEMBERS} 결과로 삭제 대상을 정하는 이 흐름을
	 * 표현할 방법이 없다(대안은 {@code WATCH} + 재시도 루프뿐이다). {@link #SAVE}와 달리
	 * <b>이 선택은 취향이 아니다</b> — 트랜잭션으로 바꾸면 위 경합이 돌아오고, <b>그것을 잡는
	 * 테스트는 없다</b>(원자성은 구조로만 보장되는 성질이다).
	 */
	private static final RedisScript<Long> REVOKE_ALL = RedisScript.of("""
		local ids = redis.call('SMEMBERS', KEYS[1])
		for i = 1, #ids do
			redis.call('DEL', ARGV[1] .. ids[i])
		end
		redis.call('DEL', KEYS[1])
		return #ids
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public RefreshTokenStore(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void save(Long memberId, String tokenId, Duration ttl) {
		redisTemplate.execute(
			SAVE,
			List.of(tokenKey(memberId, tokenId), indexKey(memberId)),
			tokenId, String.valueOf(Math.max(1, ttl.toSeconds())));
	}

	/**
	 * 소비와 발급을 한 연산으로 처리한다. 회전은 이 메서드로만 한다 — {@link #consume} 뒤에
	 * {@link #save}를 부르면 그 사이로 폐기가 지나간다({@link #ROTATE}).
	 *
	 * @return 옛 토큰이 유효해 회전에 성공하면 {@code true}. 이미 회전·폐기됐으면 {@code false}이고
	 *         이때 새 토큰은 저장되지 않는다
	 */
	public boolean rotate(Long memberId, String consumedTokenId, String issuedTokenId, Duration ttl) {
		Long rotated = redisTemplate.execute(
			ROTATE,
			List.of(
				tokenKey(memberId, consumedTokenId),
				tokenKey(memberId, issuedTokenId),
				indexKey(memberId)),
			consumedTokenId, issuedTokenId, String.valueOf(Math.max(1, ttl.toSeconds())));
		return Long.valueOf(1L).equals(rotated);
	}

	/**
	 * 토큰을 소비하고 회원별 인덱스에서도 뺀다. 이후 같은 {@code jti}로는 실패한다.
	 *
	 * <p>인덱스에서 빼는 것은 정리일 뿐 폐기의 정확성과 무관하다 — 남아 있어도
	 * {@link #revokeAll}은 없는 키를 지우고 넘어간다. 그래서 이 둘은 원자적일 필요가 없다.
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
	 * <p>재사용 감지(유출 신호)와 회원 탈퇴가 같은 연산을 필요로 한다.
	 *
	 * @return 폐기한 토큰 수. 이미 비어 있었으면 {@code 0}
	 */
	public int revokeAll(Long memberId) {
		Long revoked = redisTemplate.execute(
			REVOKE_ALL, List.of(indexKey(memberId)), TOKEN_KEY_PREFIX + memberId + ":");
		return revoked == null ? 0 : revoked.intValue();
	}

	private String tokenKey(Long memberId, String tokenId) {
		return TOKEN_KEY_PREFIX + memberId + ":" + tokenId;
	}

	private String indexKey(Long memberId) {
		return INDEX_KEY_PREFIX + memberId;
	}
}
