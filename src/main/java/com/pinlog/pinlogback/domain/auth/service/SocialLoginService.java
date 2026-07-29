package com.pinlog.pinlogback.domain.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pinlog.pinlogback.domain.auth.dto.OAuthUserInfo;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;

/**
 * 소셜 인증 결과로 회원을 찾거나 만든다(API 명세 3.2).
 *
 * <p>로그인과 가입이 같은 흐름이다. 활성 social_account가 있으면 로그인, 없으면 이 시점에
 * 회원을 만든다. 약관 동의는 클라이언트가 로그인 시작 이전 화면에서 전담하므로 서버는 관여하지 않는다.
 */
@Service
public class SocialLoginService {

	private final MemberRepository memberRepository;
	private final SocialAccountRepository socialAccountRepository;

	public SocialLoginService(MemberRepository memberRepository, SocialAccountRepository socialAccountRepository) {
		this.memberRepository = memberRepository;
		this.socialAccountRepository = socialAccountRepository;
	}

	/**
	 * @return 로그인한 회원의 식별자
	 */
	@Transactional
	public Long login(OAuthUserInfo userInfo) {
		return socialAccountRepository
			.findByProviderAndProviderUserId(userInfo.provider(), userInfo.providerUserId())
			.map(account -> account.getMember().getId())
			.orElseGet(() -> signUp(userInfo));
	}

	private Long signUp(OAuthUserInfo userInfo) {
		Member member = memberRepository.save(Member.create());
		socialAccountRepository.save(SocialAccount.create(
			member, userInfo.provider(), userInfo.providerUserId(), userInfo.email()));
		return member.getId();
	}
}
