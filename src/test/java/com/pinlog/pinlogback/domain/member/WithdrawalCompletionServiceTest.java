package com.pinlog.pinlogback.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.pinlog.pinlogback.domain.auth.client.SocialUnlinkClient;
import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.exception.WithdrawalAccountMismatchException;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;
import com.pinlog.pinlogback.domain.member.service.MemberWithdrawalService;
import com.pinlog.pinlogback.domain.member.service.WithdrawalCompletionService;
import com.pinlog.pinlogback.support.CoreApiFixtures;

/**
 * 연결 해제와 소프트 삭제의 <b>순서</b>를 고정한다(BD-48).
 *
 * <p>해제가 먼저다. {@code social_account} 마스킹이 {@code provider_user_id}를 파기하므로 뒤집으면
 * 해제할 대상을 영구히 잃는다 — 재시도조차 불가능하다.
 *
 * <p>Bean을 덮어쓰지 않고 서비스를 손으로 조립한다. {@code @MockitoBean} 계열은 컨텍스트 캐시 키를
 * 바꿔 컨텍스트가 하나 더 뜨고, 그 결과 Hikari 커넥션이 고갈돼 무관한 테스트가 무너진 적이 있다
 * (BT-01·S15P11A705-267 작업 기록).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("탈퇴 완료")
class WithdrawalCompletionServiceTest extends CoreApiFixtures {

	private static final String ACCESS_TOKEN = "provider-access-token";

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private MemberWithdrawalService memberWithdrawalService;

	private RecordingUnlinkClient google;
	private WithdrawalCompletionService completionService;

	@BeforeEach
	void assembleWithARecordingClient() {
		google = new RecordingUnlinkClient(SocialProvider.GOOGLE);
		completionService = new WithdrawalCompletionService(
			socialAccountRepository, memberWithdrawalService, List.of(google));
	}

	@Test
	@DisplayName("공급자 연결을 끊은 뒤에 회원을 소프트 삭제한다")
	void unlinksBeforeDeleting() {
		long memberId = newMemberId();
		long accountId =
			givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-complete-1", "a@example.com");

		completionService.complete(memberId, SocialProvider.GOOGLE, "google-complete-1", ACCESS_TOKEN);

		assertThat(google.tokens).containsExactly(ACCESS_TOKEN);
		assertThat(deletedAtOf("core.member", memberId)).isNotNull();
		assertThat(deletedAtOf("core.social_account", accountId)).isNotNull();
	}

	@Test
	@DisplayName("해제가 실패하면 아무것도 지우지 않는다")
	void keepsEverythingWhenUnlinkFails() {
		long memberId = newMemberId();
		long accountId =
			givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-complete-2", "b@example.com");
		google.failing = true;

		assertThatThrownBy(() -> completionService.complete(
			memberId, SocialProvider.GOOGLE, "google-complete-2", ACCESS_TOKEN))
			.isInstanceOf(SocialUnlinkException.class);

		assertThat(deletedAtOf("core.member", memberId))
			.as("지웠다면 provider_user_id가 마스킹돼 재시도조차 못 한다")
			.isNull();
		assertThat(deletedAtOf("core.social_account", accountId)).isNull();
	}

	@Test
	@DisplayName("공급자가 인증한 계정이 탈퇴 요청 회원의 것과 다르면 거절한다")
	void rejectsWhenTheAuthenticatedAccountBelongsToSomeoneElse() {
		// 공급자 화면에서 다른 계정을 고를 수 있다. 그대로 두면 계정 B의 연결을 끊고 회원 A를
		// 지운다(BD-48 §⑤).
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-complete-3", "c@example.com");

		assertThatThrownBy(() -> completionService.complete(
			memberId, SocialProvider.GOOGLE, "somebody-else", ACCESS_TOKEN))
			.isInstanceOf(WithdrawalAccountMismatchException.class);

		assertThat(google.tokens).as("남의 연결을 끊지 않는다").isEmpty();
		assertThat(deletedAtOf("core.member", memberId)).isNull();
	}

	@Test
	@DisplayName("공급자가 달라도 거절한다")
	void rejectsWhenTheProviderDiffers() {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-complete-4", "d@example.com");

		assertThatThrownBy(() -> completionService.complete(
			memberId, SocialProvider.KAKAO, "google-complete-4", ACCESS_TOKEN))
			.isInstanceOf(WithdrawalAccountMismatchException.class);
	}

	@Test
	@DisplayName("담당 클라이언트가 없는 공급자는 지우지 않고 실패한다")
	void failsWithoutDeletingWhenNoClientHandlesTheProvider() {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.KAKAO, "kakao-complete-5", "e@example.com");

		assertThatThrownBy(() -> completionService.complete(
			memberId, SocialProvider.KAKAO, "kakao-complete-5", ACCESS_TOKEN))
			.isInstanceOf(UnsupportedSocialProviderException.class);

		assertThat(deletedAtOf("core.member", memberId)).isNull();
	}

	/** 호출 사실과 순서를 관찰하려고 손으로 만든 스텁. */
	private static final class RecordingUnlinkClient implements SocialUnlinkClient {

		private final SocialProvider provider;
		private final List<String> tokens = new ArrayList<>();
		private boolean failing;

		private RecordingUnlinkClient(SocialProvider provider) {
			this.provider = provider;
		}

		@Override
		public SocialProvider provider() {
			return provider;
		}

		@Override
		public void unlink(String accessToken) {
			if (failing) {
				throw new SocialUnlinkException("stubbed failure", new IllegalStateException());
			}
			tokens.add(accessToken);
		}
	}
}
