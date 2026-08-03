package com.pinlog.pinlogback.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.pinlog.pinlogback.global.exception.UnauthorizedException;
import com.pinlog.pinlogback.global.security.token.JwtKeyProvider;
import com.pinlog.pinlogback.global.security.token.JwtProperties;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 회전과 재사용 감지가 겹칠 때 폐기가 새지 않는지 확인한다(BD-35).
 *
 * <p>BD-35는 재사용을 유출 신호로 보고 <b>그 회원의 Refresh를 전부</b> 폐기하기로 했다. 소비와
 * 저장을 별개의 Redis 왕복으로 두면 그 사이에 동시 요청의 폐기가 끼고, 그러면 먼저 도착한 요청이
 * 발급한 토큰이 <b>폐기가 끝난 뒤에 저장되어</b> 살아남는다. 살아남은 토큰은 다음 회전의 씨앗이
 * 되어 "폐기했는데 다음 사이클에 또 살아 있는" 상태를 만든다.
 *
 * <p><b>서명 단계를 걸쇠로 쓴다.</b> 창을 벌리려면 두 요청의 순서를 고정해야 하는데, 저장 호출을
 * 붙들면 걸쇠가 곧 수정 대상이 되어 고친 뒤 테스트를 다시 써야 한다. 새 {@code jti}를 만드는 서명은
 * 저장보다 반드시 앞서므로 어느 구현에서도 남아 있는 지점이다. 여기서 멈춰 세우면 창이 열려 있을
 * 때와 닫힌 뒤에 <b>같은 테스트가 서로 다른 판정</b>을 낸다 — 열려 있으면 폐기 뒤 저장이 성립해
 * 토큰이 남고, 닫히면 둘 중 하나만 성공하고 나머지 하나가 전부 지운다.
 *
 * <p>그래서 누가 이기는지는 단정하지 않는다. 창이 닫히면 승패가 뒤집히는데 그것은 계약이 아니다.
 * 계약은 <b>둘 중 하나만 성공하고, 끝난 뒤 그 회원의 토큰이 하나도 남지 않는다</b>이다.
 *
 * <p>대역을 빈으로 갈아끼우지 않고 서비스를 직접 조립한다. 빈 오버라이드는 컨텍스트 캐시 키를
 * 바꿔 컨텍스트를 하나 더 띄우는데, 그만큼 커넥션 풀이 늘어 공유 PostgreSQL이
 * {@code too many clients}로 거절한다(실측: 이 클래스만으로 다른 테스트 10개가 깨졌다).
 */
@SpringBootTest
@DisplayName("Refresh 회전과 재사용 감지의 경합")
class AuthTokenServiceRotationRaceTest extends IntegrationContainerSupport {

	private static final long MEMBER_ID = 92_001L;
	private static final long TIMEOUT_SECONDS = 5L;

	@Autowired
	private RefreshTokenStore refreshTokenStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private JwtKeyProvider jwtKeyProvider;

	@Test
	@DisplayName("회전이 발급한 토큰도 같은 순간의 폐기를 넘기지 못한다")
	void rotationDoesNotOutliveConcurrentRevocation() throws Exception {
		PausingTokenProvider tokenProvider = new PausingTokenProvider(jwtProperties, jwtKeyProvider);
		AuthTokenService authTokenService =
			new AuthTokenService(tokenProvider, refreshTokenStore, jwtProperties);
		String refreshToken = authTokenService.issue(MEMBER_ID).refreshToken();

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Throwable held;
		Throwable racing;
		try {
			Future<Throwable> future =
				executor.submit(() -> catchThrowable(() -> authTokenService.rotate(refreshToken)));

			assertThat(tokenProvider.awaitSigning(TIMEOUT_SECONDS))
				.as("서명 단계에서 멈춰 세우지 못하면 경합을 재현한 것이 아니다")
				.isTrue();

			racing = catchThrowable(() -> authTokenService.rotate(refreshToken));

			tokenProvider.release();
			held = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		// 같은 토큰을 두 번 내밀었으므로 한쪽은 반드시 재사용으로 걸린다. 둘 다 실패하면 아래
		// "남은 토큰 없음"이 옳은 이유로 성립한 것이 아니므로 그것부터 막는다.
		assertThat(failuresAmong(held, racing))
			.as("한쪽은 성공하고 다른 한쪽은 재사용으로 걸려야 한다")
			.isEqualTo(1);
		assertThat(held == null ? racing : held).isInstanceOf(UnauthorizedException.class);

		assertThat(redisTemplate.keys("auth:refresh:" + MEMBER_ID + ":*"))
			.as("폐기 뒤에 저장된 토큰이 살아남으면 BD-35의 전 세션 폐기가 새는 것이다")
			.isEmpty();
	}

	private int failuresAmong(Throwable... outcomes) {
		int failures = 0;
		for (Throwable outcome : outcomes) {
			if (outcome != null) {
				failures++;
			}
		}
		return failures;
	}

	/**
	 * 두 번째 서명에서 멈춰 선다.
	 *
	 * <p>순서는 이렇다 — 1번은 준비 단계의 발급이라 붙들면 깨워 줄 사람이 없고, 2번이 먼저 출발한
	 * 회전이다. 3번은 그 회전이 멈춰 선 동안 뒤따라온 회전이므로 지나가야 경합이 성립한다.
	 */
	private static final class PausingTokenProvider extends JwtTokenProvider {

		private static final int RACING_ROTATION = 2;

		private final CountDownLatch reachedSigning = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private final AtomicInteger signings = new AtomicInteger();

		private PausingTokenProvider(JwtProperties properties, JwtKeyProvider keyProvider) {
			super(properties, keyProvider);
		}

		@Override
		public IssuedRefreshToken issueRefreshToken(Long memberId) {
			if (signings.incrementAndGet() == RACING_ROTATION) {
				reachedSigning.countDown();
				awaitRelease();
			}
			return super.issueRefreshToken(memberId);
		}

		private boolean awaitSigning(long seconds) throws InterruptedException {
			return reachedSigning.await(seconds, TimeUnit.SECONDS);
		}

		private void release() {
			released.countDown();
		}

		private void awaitRelease() {
			try {
				released.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("서명 대기가 중단됐다", e);
			}
		}
	}
}
