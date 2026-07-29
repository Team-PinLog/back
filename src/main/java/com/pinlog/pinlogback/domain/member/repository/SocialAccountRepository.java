package com.pinlog.pinlogback.domain.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

	/**
	 * 소셜 로그인 콜백에서 기존 회원 여부를 판정한다.
	 * {@code @SQLRestriction} 때문에 활성 행만 조회되므로, 탈퇴한 계정은 비어 있는 결과가 되어
	 * 신규 가입 흐름을 탄다(docs/static/08_API_명세.md 3.2).
	 */
	Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
}
