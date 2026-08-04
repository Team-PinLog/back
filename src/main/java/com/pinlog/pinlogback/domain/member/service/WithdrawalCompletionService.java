package com.pinlog.pinlogback.domain.member.service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.domain.auth.client.SocialUnlinkClient;
import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.exception.WithdrawalAccountMismatchException;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴 인가 왕복이 돌아온 뒤의 나머지 절반(BD-48).
 *
 * <p><b>순서가 이 클래스의 존재 이유다.</b> 연결 해제가 먼저이고 소프트 삭제가 나중이다.
 * {@code social_account} 마스킹이 {@code provider_user_id}를 파기하므로, 뒤집으면 공급자에서 그
 * 사용자를 지목할 수단이 <b>영구히</b> 사라진다 — 실패를 큐에 넣어 나중에 재시도할 수도 없다.
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다.</b> 해제는 외부 HTTP 호출이라 트랜잭션 안에 두면
 * 공급자 응답을 기다리는 동안 DB 커넥션을 잡고 있게 된다. 삭제 쪽은
 * {@link MemberWithdrawalService#withdraw}가 자기 트랜잭션을 연다.
 *
 * <p>그래서 남는 창은 하나다 — <b>해제는 성공했는데 삭제가 실패</b>. 이 방향의 잔여 위험은 무해하다.
 * 공급자 연결은 이미 끊겼고 회원 데이터는 그대로이므로 사용자가 탈퇴를 다시 시도하면 된다(공급자는
 * 이미 폐기된 토큰에도 성공을 준다). 반대 방향은 복구가 없다.
 */
@Slf4j
@Service
public class WithdrawalCompletionService {

	/** 첫 시도를 포함한 횟수. 근거는 {@link #unlinkAbsorbingTransientFailure}에 있다. */
	private static final int MAX_UNLINK_ATTEMPTS = 3;
	private static final Duration RETRY_BACKOFF = Duration.ofMillis(200);

	private final SocialAccountRepository socialAccountRepository;
	private final MemberWithdrawalService memberWithdrawalService;
	private final Map<SocialProvider, SocialUnlinkClient> unlinkClients;

	public WithdrawalCompletionService(
		SocialAccountRepository socialAccountRepository,
		MemberWithdrawalService memberWithdrawalService,
		List<SocialUnlinkClient> unlinkClients
	) {
		this.socialAccountRepository = socialAccountRepository;
		this.memberWithdrawalService = memberWithdrawalService;
		this.unlinkClients = new EnumMap<>(SocialProvider.class);
		unlinkClients.forEach(client -> this.unlinkClients.put(client.provider(), client));
	}

	/**
	 * @param memberId 탈퇴를 요청한 회원. 인가 요청 {@code attributes}에서 온다 — 왕복 중 Access가
	 *     만료될 수 있어 쿠키로 다시 식별하지 않는다
	 * @param provider 방금 인증한 공급자
	 * @param providerUserId 방금 인증한 공급자 계정
	 * @param accessToken 방금 발급받은 공급자 access token
	 * @throws WithdrawalAccountMismatchException 인증된 계정이 그 회원의 것이 아닐 때
	 * @throws UnsupportedSocialProviderException 그 공급자를 해제할 클라이언트가 없을 때
	 * @throws com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException 해제가 실패했을 때
	 */
	public void complete(
		Long memberId, SocialProvider provider, String providerUserId, String accessToken) {
		List<SocialAccount> accounts = socialAccountRepository.findByMemberId(memberId);
		if (accounts.isEmpty()) {
			// 탭 두 개로 동시에 탈퇴하면 두 번째가 늦게 도착한다. 실패로 다루면 프론트가 거짓을
			// 말하므로 완료로 본다.
			//
			// 이 왕복이 만든 새 인가는 끊지 않는다. 마스킹으로 원본 식별자가 사라져 방금 인증된
			// 계정이 그 회원의 것인지 확인할 수 없고, 확인 없이 끊으면 남의 연결을 끊는 경로가
			// 열린다(BD-48 §⑤). 남는 연결은 사용자가 공급자 설정에서 지울 수 있다.
			log.info("withdrawal already completed, treating as done: memberId={}", memberId);
			return;
		}
		requireSingleAccount(memberId, accounts);
		requireOwnAccount(memberId, provider, providerUserId, accounts);

		SocialUnlinkClient client = unlinkClients.get(provider);
		if (client == null) {
			throw new UnsupportedSocialProviderException(provider.registrationId());
		}
		unlinkAbsorbingTransientFailure(client, accessToken, memberId);
		log.info("social account unlinked before withdrawal: memberId={}, provider={}",
			memberId, provider);

		memberWithdrawalService.withdraw(memberId);
	}

	/**
	 * 일시적 장애를 짧은 재시도로 흡수한다.
	 *
	 * <p><b>이것이 없으면 공급자의 503 한 번이 탈퇴를 영구히 막는다.</b> 해제를 선행으로 두면서
	 * "공급자 장애가 탈퇴를 막아서는 안 된다"던 결정을 뒤집었는데(BD-48), 그 판단이 성립하는 조건이
	 * <b>실제 거절이 드물다</b>는 것이다. 순간적 장애를 흡수하지 않으면 그 조건이 깨진다.
	 *
	 * <p>되풀이가 안전한 근거는 멱등성이다 — 이미 폐기된 토큰에도 세 공급자 모두 성공을 준다
	 * (RFC 7009). 그래서 첫 요청이 실제로는 성공했는데 응답만 못 받은 경우에도 두 번째가 깨지지
	 * 않는다.
	 *
	 * <p><b>사용자가 리다이렉트 뒤에서 기다리는 구간이라 짧게 잡는다.</b> 3회·0.2초·0.4초이고,
	 * 공급자가 응답을 주는 한 대기는 0.6초를 넘지 않는다. 최악은 타임아웃이 세 번 나는 경우로
	 * 읽기 5초 × 3 + 0.6초다. 그보다 길게 잡으면 흡수하는 장애의 폭보다 기다리는 시간이 먼저 커진다.
	 *
	 * <p>재시도가 의미 없는 실패는 즉시 올린다. 자격증명 오류나 요청 형식 오류는 몇 번을 보내도
	 * 같아서, 되풀이하면 사용자를 기다리게 할 뿐이다.
	 */
	private void unlinkAbsorbingTransientFailure(
		SocialUnlinkClient client, String accessToken, Long memberId) {
		for (int attempt = 1; ; attempt++) {
			try {
				client.unlink(accessToken);
				return;
			} catch (SocialUnlinkException e) {
				if (!e.isRetryable() || attempt == MAX_UNLINK_ATTEMPTS) {
					throw e;
				}
				log.warn("unlink attempt {} failed, retrying: memberId={}, provider={}",
					attempt, memberId, client.provider(), e);
				sleep(RETRY_BACKOFF.multipliedBy(attempt));
			}
		}
	}

	/**
	 * 인터럽트를 삼키지 않고 되살린다. 여기서 삼키면 종료 신호를 받은 스레드가 남은 재시도를 계속
	 * 돌아 graceful shutdown 예산을 잠식한다.
	 */
	private void sleep(Duration backoff) {
		try {
			Thread.sleep(backoff);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SocialUnlinkException("unlink retry interrupted", e, false);
		}
	}

	/**
	 * 한 번의 왕복은 한 공급자만 인가하는데 소프트 삭제는 그 회원의 계정을 전부 마스킹한다. 둘
	 * 있는데 하나만 끊고 지우면 나머지는 <b>영구히 못 끊는다</b>. {@code WithdrawalAuthorizationService}가
	 * 먼저 막지만 그 보증이 다른 클래스에 있어 여기서 한 번 더 확인한다 — 되돌릴 수 없는 쪽이다.
	 */
	private void requireSingleAccount(Long memberId, List<SocialAccount> accounts) {
		if (accounts.size() > 1) {
			throw new IllegalStateException(
				"한 번의 인가 왕복으로는 계정 " + accounts.size() + "개를 해제할 수 없다: memberId=" + memberId);
		}
	}

	/** 공급자 화면에서 다른 계정을 고를 수 있다. 확인하지 않으면 남의 연결을 끊는다(BD-48 §⑤). */
	private void requireOwnAccount(
		Long memberId, SocialProvider provider, String providerUserId, List<SocialAccount> accounts) {
		boolean owned = accounts.stream().anyMatch(account -> matches(account, provider, providerUserId));
		if (!owned) {
			throw new WithdrawalAccountMismatchException(memberId);
		}
	}

	private boolean matches(SocialAccount account, SocialProvider provider, String providerUserId) {
		return account.getProvider() == provider && account.getProviderUserId().equals(providerUserId);
	}
}
