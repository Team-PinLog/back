package com.pinlog.pinlogback.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 저장 구조 자체의 불변식을 Redis에 직접 물어 확인한다.
 *
 * <p>{@code AuthTokenContractTests}가 HTTP 경계에서 보는 것과 겹치지 않는 것만 둔다 — 만료·키
 * 정리처럼 <b>바깥에서 관측되지 않는 성질</b>이다. 이런 것은 깨져도 어떤 계약 테스트도 실패하지
 * 않고, 증상이 몇 일 뒤 운영 Redis에서 나타난다.
 *
 * <p>키 형식을 테스트가 다시 적는다. 프로덕션과 문자열이 중복되지만, 그러지 않으면 만료를 물어볼
 * 대상을 지목할 수 없다. 이 중복은 <b>키 이름도 계약</b>이라는 뜻으로 받아들인다.
 */
@SpringBootTest
@DisplayName("Refresh 토큰 저장소")
class RefreshTokenStoreTest extends IntegrationContainerSupport {

	private static final Duration TTL = Duration.ofDays(7);

	@Autowired
	private RefreshTokenStore store;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Test
	@DisplayName("발급은 회원별 인덱스에 만료를 함께 건다")
	void saveGivesTheIndexAnExpiry() {
		// SADD는 TTL을 만들지 않는다. EXPIRE 한 줄이 빠지면 인덱스가 만료 없는 키로 남아
		// 회원 수만큼 쌓이는데, 그때 증상은 "Redis에 영구 키가 늘어난다"뿐이고 계약 테스트는
		// 전부 통과한다. 그 한 줄을 지켜 주는 단언이다.
		long memberId = 90_001L;

		store.save(memberId, "jti-expiry", TTL);

		assertThat(redisTemplate.getExpire(indexKey(memberId)))
			.as("인덱스에 만료가 없으면 영구 키가 된다")
			.isGreaterThan(0);
		assertThat(redisTemplate.getExpire(tokenKey(memberId, "jti-expiry")))
			.isGreaterThan(0);
	}

	@Test
	@DisplayName("폐기는 그 회원의 토큰 키와 인덱스를 모두 지운다")
	void revokeAllRemovesEveryTokenAndTheIndex() {
		long memberId = 90_002L;
		store.save(memberId, "jti-a", TTL);
		store.save(memberId, "jti-b", TTL);

		int revoked = store.revokeAll(memberId);

		assertThat(revoked).isEqualTo(2);
		assertThat(redisTemplate.hasKey(tokenKey(memberId, "jti-a"))).isFalse();
		assertThat(redisTemplate.hasKey(tokenKey(memberId, "jti-b"))).isFalse();
		assertThat(redisTemplate.hasKey(indexKey(memberId)))
			.as("인덱스를 남기면 다음 폐기가 이미 사라진 jti를 다시 훑는다")
			.isFalse();
		assertThat(store.consume(memberId, "jti-a"))
			.as("폐기 후에는 어떤 토큰도 소비되지 않아야 한다")
			.isFalse();
	}

	@Test
	@DisplayName("폐기는 다른 회원의 토큰을 건드리지 않는다")
	void revokeAllIsScopedToOneMember() {
		// 인덱스 키가 회원별로 갈리는 것에 의존하는 성질이라, 키 조립이 틀리면 여기서 걸린다.
		long revoked = 90_003L;
		long untouched = 90_004L;
		store.save(revoked, "jti-mine", TTL);
		store.save(untouched, "jti-theirs", TTL);

		store.revokeAll(revoked);

		assertThat(redisTemplate.hasKey(tokenKey(untouched, "jti-theirs"))).isTrue();
		assertThat(store.consume(untouched, "jti-theirs")).isTrue();
	}

	@Test
	@DisplayName("소비한 토큰은 인덱스에서도 빠진다")
	void consumeDropsTheTokenFromTheIndex() {
		long memberId = 90_005L;
		store.save(memberId, "jti-consumed", TTL);
		store.save(memberId, "jti-alive", TTL);

		assertThat(store.consume(memberId, "jti-consumed")).isTrue();

		assertThat(store.revokeAll(memberId))
			.as("소비된 jti가 인덱스에 남아 있으면 폐기 수가 2가 된다")
			.isEqualTo(1);
	}

	@Test
	@DisplayName("폐기할 것이 없으면 0을 돌려준다")
	void revokeAllOnEmptyIndexIsZero() {
		assertThat(store.revokeAll(90_006L)).isZero();
	}

	@Test
	@DisplayName("회전은 옛 토큰을 소비하고 새 토큰을 남긴다")
	void rotateConsumesTheOldTokenAndKeepsTheNewOne() {
		long memberId = 90_008L;
		store.save(memberId, "jti-old", TTL);

		assertThat(store.rotate(memberId, "jti-old", "jti-new", TTL)).isTrue();

		assertThat(redisTemplate.hasKey(tokenKey(memberId, "jti-old"))).isFalse();
		assertThat(redisTemplate.hasKey(tokenKey(memberId, "jti-new"))).isTrue();
		assertThat(store.revokeAll(memberId))
			.as("옛 jti가 인덱스에 남아 있으면 2가 된다")
			.isEqualTo(1);
	}

	@Test
	@DisplayName("이미 소비된 토큰으로 회전하면 새 토큰을 남기지 않는다")
	void rotateOnConsumedTokenWritesNothing() {
		// 실패한 회전이 새 토큰을 남기면, 뒤이어 도는 폐기가 그것을 보지 못해 살아남는다.
		long memberId = 90_009L;

		assertThat(store.rotate(memberId, "jti-gone", "jti-would-be", TTL)).isFalse();

		assertThat(redisTemplate.hasKey(tokenKey(memberId, "jti-would-be"))).isFalse();
		assertThat(store.revokeAll(memberId)).isZero();
	}

	private String tokenKey(long memberId, String tokenId) {
		return "auth:refresh:" + memberId + ":" + tokenId;
	}

	private String indexKey(long memberId) {
		return "auth:refresh-index:" + memberId;
	}
}
