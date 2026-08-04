package com.pinlog.pinlogback.domain.member.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.domain.auth.client.SocialUnlinkClient;
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
		requireOwnAccount(memberId, provider, providerUserId);

		SocialUnlinkClient client = unlinkClients.get(provider);
		if (client == null) {
			throw new UnsupportedSocialProviderException(provider.registrationId());
		}
		client.unlink(accessToken);
		log.info("social account unlinked before withdrawal: memberId={}, provider={}",
			memberId, provider);

		memberWithdrawalService.withdraw(memberId);
	}

	/** 공급자 화면에서 다른 계정을 고를 수 있다. 확인하지 않으면 남의 연결을 끊는다(BD-48 §⑤). */
	private void requireOwnAccount(Long memberId, SocialProvider provider, String providerUserId) {
		boolean owned = socialAccountRepository.findByMemberId(memberId).stream()
			.anyMatch(account -> matches(account, provider, providerUserId));
		if (!owned) {
			throw new WithdrawalAccountMismatchException(memberId);
		}
	}

	private boolean matches(SocialAccount account, SocialProvider provider, String providerUserId) {
		return account.getProvider() == provider && account.getProviderUserId().equals(providerUserId);
	}
}
