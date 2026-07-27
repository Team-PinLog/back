package com.pinlog.pinlogback.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
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
@DisplayName("SocialAccount 영속성")
class SocialAccountPersistenceTests extends PostgresContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("회원에 연결된 소셜 계정을 저장한다")
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
	@DisplayName("provider와 provider_user_id로 활성 계정을 조회한다")
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
	@DisplayName("이메일 없이도 저장되고 컬럼에 null로 남는다")
	void emailIsOptional() {
		// 공급자가 이메일을 제공하지 않거나 사용자가 동의하지 않으면 null이다(06 2.2).
		// email에 NOT NULL이 걸려 있으면 아래 flush에서 실패한다.
		Member member = memberRepository.save(Member.create());

		SocialAccount saved = socialAccountRepository.saveAndFlush(
			SocialAccount.create(member, SocialProvider.NAVER, "naver-7", null));

		Long rowsWithNullEmail = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.social_account WHERE id = ? AND email IS NULL", Long.class, saved.getId());

		assertThat(rowsWithNullEmail).isEqualTo(1L);
	}

	@Test
	@DisplayName("같은 공급자 계정이 이미 활성이면 저장을 거부한다")
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
	@DisplayName("소프트 삭제 후에는 같은 공급자 계정으로 다시 가입할 수 있다")
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
	@DisplayName("소프트 삭제한 계정은 물리 행으로 남고 조회에서만 제외된다")
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
