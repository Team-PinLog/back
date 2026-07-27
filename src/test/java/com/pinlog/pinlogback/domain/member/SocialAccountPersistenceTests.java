package com.pinlog.pinlogback.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@SpringBootTest(properties = "management.health.redis.enabled=false")
class SocialAccountPersistenceTests extends PostgresContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void savesSocialAccountLinkedToMember() {
		Member member = memberRepository.save(Member.create());

		SocialAccount saved = socialAccountRepository.save(
			SocialAccount.create(member, SocialProvider.GOOGLE, "google-sub-1", "user@example.com"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.isDeleted()).isFalse();
		assertThat(saved.getMember().getId()).isEqualTo(member.getId());
	}

	@Test
	void findsActiveAccountByProviderAndProviderUserId() {
		Member member = memberRepository.save(Member.create());
		socialAccountRepository.save(
			SocialAccount.create(member, SocialProvider.KAKAO, "kakao-42", null));

		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-42"))
			.isPresent();
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, "kakao-42"))
			.isEmpty();
	}

	@Test
	void emailIsOptional() {
		// 공급자가 미제공·미동의하면 null이다(06 2.2).
		Member member = memberRepository.save(Member.create());

		SocialAccount saved = socialAccountRepository.save(
			SocialAccount.create(member, SocialProvider.NAVER, "naver-7", null));

		assertThat(saved.getEmail()).isNull();
	}

	@Test
	void rejectsDuplicateActiveProviderAccount() {
		Member first = memberRepository.save(Member.create());
		Member second = memberRepository.save(Member.create());
		socialAccountRepository.saveAndFlush(
			SocialAccount.create(first, SocialProvider.GOOGLE, "google-dup", "a@example.com"));

		assertThatThrownBy(() -> socialAccountRepository.saveAndFlush(
			SocialAccount.create(second, SocialProvider.GOOGLE, "google-dup", "b@example.com")))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsReSignupWithSameProviderAccountAfterSoftDelete() {
		Member first = memberRepository.save(Member.create());
		SocialAccount account = socialAccountRepository.saveAndFlush(
			SocialAccount.create(first, SocialProvider.GOOGLE, "google-rejoin", "a@example.com"));

		account.softDelete();
		socialAccountRepository.saveAndFlush(account);

		Member second = memberRepository.save(Member.create());
		SocialAccount rejoined = socialAccountRepository.saveAndFlush(
			SocialAccount.create(second, SocialProvider.GOOGLE, "google-rejoin", "b@example.com"));

		assertThat(rejoined.getId()).isNotEqualTo(account.getId());
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-rejoin"))
			.get()
			.extracting(SocialAccount::getId)
			.isEqualTo(rejoined.getId());
	}

	@Test
	void softDeletedAccountRemainsAsPhysicalRow() {
		Member member = memberRepository.save(Member.create());
		SocialAccount account = socialAccountRepository.saveAndFlush(
			SocialAccount.create(member, SocialProvider.GOOGLE, "google-soft", "a@example.com"));
		Long id = account.getId();

		account.softDelete();
		socialAccountRepository.saveAndFlush(account);

		Long physicalRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.social_account WHERE id = ? AND deleted_at IS NOT NULL", Long.class, id);

		assertThat(physicalRows).isEqualTo(1L);
		assertThat(socialAccountRepository.findById(id)).isEmpty();
	}
}
