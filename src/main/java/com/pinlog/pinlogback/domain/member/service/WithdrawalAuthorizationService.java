package com.pinlog.pinlogback.domain.member.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.pinlog.pinlogback.domain.member.dto.WithdrawalStartResponse;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;
import com.pinlog.pinlogback.global.exception.UnauthorizedException;
import com.pinlog.pinlogback.global.security.oauth.OAuthEndpointPaths;
import com.pinlog.pinlogback.global.security.oauth.WithdrawalAwareAuthorizationRequestResolver;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;

/**
 * 탈퇴 인가 왕복의 시작(BD-48 §②).
 *
 * <p><b>{@link MemberWithdrawalService}와 나눠 둔다.</b> 그쪽은 해제가 끝난 뒤 도는 삭제 순서를
 * 한 트랜잭션에 담는 것이 존재 이유고, 여기는 그 앞에서 왕복을 시작할 권한만 만든다. 리포지토리도
 * 하나만 쓰고 겹치는 상태가 없다.
 *
 * <p>이 시점에는 <b>아무것도 지우지 않는다.</b> 마스킹이 공급자 식별자를 파기하므로 먼저 지우면
 * 해제할 대상을 잃는다.
 */
@Service
public class WithdrawalAuthorizationService {

	private final SocialAccountRepository socialAccountRepository;
	private final JwtTokenProvider tokenProvider;
	private final String contextPath;

	public WithdrawalAuthorizationService(
		SocialAccountRepository socialAccountRepository,
		JwtTokenProvider tokenProvider,
		@Value("${server.servlet.context-path}") String contextPath
	) {
		this.socialAccountRepository = socialAccountRepository;
		this.tokenProvider = tokenProvider;
		this.contextPath = contextPath;
	}

	/**
	 * 탈퇴할 회원의 공급자를 찾아 그 공급자의 인가 진입 URL을 만든다.
	 *
	 * <p>공급자를 클라이언트에게 받지 않는다 — 받으면 A로 로그인한 회원이 B의 화면으로 갈 수 있고,
	 * 그 결과는 해제 없이 끝나는 탈퇴다. 서버가 {@code social_account}에서 정한다.
	 *
	 * @throws UnauthorizedException 활성 소셜 계정이 없을 때. 해제할 대상이 없으면 탈퇴를 시작할
	 *     수 없다 — 인증을 통과했는데 계정이 없다는 것은 이미 탈퇴했거나 데이터가 깨진 상태다
	 * @throws IllegalStateException 계정이 둘 이상일 때. 근거는 {@link #requireSingleAccount}에 있다
	 */
	@Transactional(readOnly = true)
	public WithdrawalStartResponse start(Long memberId) {
		SocialAccount account = requireSingleAccount(memberId);

		String authorizationUrl = UriComponentsBuilder
			.fromPath(contextPath + OAuthEndpointPaths.AUTHORIZATION_BASE_URI)
			.pathSegment(account.getProvider().registrationId())
			.queryParam(WithdrawalAwareAuthorizationRequestResolver.TICKET_PARAMETER,
				tokenProvider.issueWithdrawalTicket(memberId))
			.build()
			.toUriString();
		return new WithdrawalStartResponse(authorizationUrl);
	}

	/**
	 * 계정이 정확히 하나일 때만 시작한다.
	 *
	 * <p><b>한 번의 왕복은 한 공급자만 인가한다.</b> 그런데 소프트 삭제는 그 회원의 모든
	 * {@code social_account}를 마스킹하므로, 둘 있는데 하나만 끊고 지우면 나머지는
	 * {@code provider_user_id}가 파기돼 <b>영구히 못 끊는다</b> — 이 설계가 막으려던 바로 그 상태다.
	 *
	 * <p>지금은 도달할 수 없다. {@code SocialAccount.create}를 부르는 곳이 로그인 하나뿐이라 회원당
	 * 계정이 하나다. 그래도 닫아 두는 이유는 결과가 되돌릴 수 없고, 계정 연결 기능이 붙는 순간
	 * <b>조용히</b> 깨지기 때문이다. 여기서 막히면 그 기능을 만드는 사람이 왕복을 공급자 수만큼
	 * 도는 흐름을 함께 설계하게 된다.
	 */
	private SocialAccount requireSingleAccount(Long memberId) {
		List<SocialAccount> accounts = socialAccountRepository.findByMemberId(memberId);
		if (accounts.isEmpty()) {
			throw new UnauthorizedException();
		}
		if (accounts.size() > 1) {
			throw new IllegalStateException(
				"한 번의 인가 왕복으로는 계정 " + accounts.size() + "개를 해제할 수 없다: memberId=" + memberId);
		}
		return accounts.getFirst();
	}
}
