package com.pinlog.pinlogback.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

	/** 실제 조립 결과. 위 스텁과 달리 이것은 컨텍스트가 무엇을 찾았는지를 보여 준다. */
	@Autowired
	private List<SocialUnlinkClient> registeredUnlinkClients;

	private RecordingUnlinkClient google;
	private WithdrawalCompletionService completionService;

	@BeforeEach
	void assembleWithARecordingClient() {
		google = new RecordingUnlinkClient(SocialProvider.GOOGLE);
		completionService = new WithdrawalCompletionService(
			socialAccountRepository, memberWithdrawalService, List.of(google));
	}

	@Test
	@DisplayName("지원하는 세 공급자 모두 해제 클라이언트를 갖는다")
	void everySupportedProviderHasAnUnlinkClient() {
		// 하나가 Bean으로 안 잡혀도 컴파일과 나머지 테스트는 통과한다. 증상은 그 공급자로 가입한
		// 회원만 탈퇴하지 못하는 것이라 배포 후에야 드러난다.
		assertThat(registeredUnlinkClients).extracting(SocialUnlinkClient::provider)
			.containsExactlyInAnyOrder(SocialProvider.values());
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
	@DisplayName("순간적 장애는 재시도가 흡수하고 탈퇴는 확정된다")
	void transientFailureIsAbsorbedByRetry() {
		// 이 재시도가 없으면 공급자의 503 한 번이 탈퇴를 영구히 막는다. 214를 뒤집으면서
		// "실제 거절은 드물어진다"고 한 근거가 이것이다.
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-retry-1", "f@example.com");
		google.transientFailuresLeft = 2;

		completionService.complete(memberId, SocialProvider.GOOGLE, "google-retry-1", ACCESS_TOKEN);

		assertThat(google.attempts).isEqualTo(3);
		assertThat(deletedAtOf("core.member", memberId)).isNotNull();
	}

	@Test
	@DisplayName("재시도해도 소용없는 실패는 되풀이하지 않는다")
	void permanentFailureIsNotRetried() {
		// 자격증명이 틀렸거나 요청 형식이 어긋난 것은 몇 번을 보내도 같다. 되풀이하면 사용자를
		// 기다리게 할 뿐이다.
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-retry-2", "g@example.com");
		google.failing = true;
		google.retryable = false;

		assertThatThrownBy(() -> completionService.complete(
			memberId, SocialProvider.GOOGLE, "google-retry-2", ACCESS_TOKEN))
			.isInstanceOf(SocialUnlinkException.class);

		assertThat(google.attempts).isEqualTo(1);
		assertThat(deletedAtOf("core.member", memberId)).isNull();
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
	@DisplayName("소셜 계정이 여럿이면 지우지 않고 멈춘다")
	void refusesWhenTheMemberHasMoreThanOneSocialAccount() {
		// 한 번의 왕복은 한 공급자만 인가한다. 하나만 끊고 마스킹하면 나머지는 provider_user_id가
		// 파기돼 영구히 못 끊는다 — 이 설계가 막으려던 바로 그 상태다.
		// 지금은 도달할 수 없지만(계정 생성 경로가 로그인 하나뿐) 결과가 영구적이라 닫아 둔다.
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-multi-1", "h@example.com");
		givenSocialAccount(memberId, SocialProvider.KAKAO, "kakao-multi-1", "h@example.com");

		assertThatThrownBy(() -> completionService.complete(
			memberId, SocialProvider.GOOGLE, "google-multi-1", ACCESS_TOKEN))
			.isInstanceOf(IllegalStateException.class);

		assertThat(google.attempts).as("끊을 수 없는 계정이 남으므로 시작도 하지 않는다").isZero();
		assertThat(deletedAtOf("core.member", memberId)).isNull();
	}

	@Test
	@DisplayName("이미 탈퇴한 회원의 두 번째 왕복은 완료로 본다")
	void secondRoundTripOfAnAlreadyWithdrawnMemberIsIdempotent() {
		// 탭 두 개로 동시에 탈퇴하면 두 번째가 늦게 도착한다. 소프트 삭제된 계정은 조회되지 않아
		// 소유 확인이 실패하는데, 그것을 "남의 계정"과 같이 다루면 프론트가 거짓을 말한다.
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-twice-1", "i@example.com");
		completionService.complete(memberId, SocialProvider.GOOGLE, "google-twice-1", ACCESS_TOKEN);

		assertThatCode(() -> completionService.complete(
			memberId, SocialProvider.GOOGLE, "google-twice-1", ACCESS_TOKEN))
			.doesNotThrowAnyException();

		// 마스킹으로 원본 식별자가 사라져 이 계정이 그 회원의 것인지 확인할 수 없다. 확인 없이
		// 끊으면 남의 연결을 끊는 경로가 열리므로(§⑤) 두 번째는 호출하지 않는다.
		assertThat(google.attempts).isEqualTo(1);
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

	/** 호출 사실과 횟수를 관찰하려고 손으로 만든 스텁. */
	private static final class RecordingUnlinkClient implements SocialUnlinkClient {

		private final SocialProvider provider;
		private final List<String> tokens = new ArrayList<>();
		private int attempts;
		private boolean failing;
		private boolean retryable = true;
		/** 이 횟수만큼 일시적 장애를 내고 그 뒤엔 성공한다. */
		private int transientFailuresLeft;

		private RecordingUnlinkClient(SocialProvider provider) {
			this.provider = provider;
		}

		@Override
		public SocialProvider provider() {
			return provider;
		}

		@Override
		public void unlink(String accessToken) {
			attempts++;
			if (transientFailuresLeft > 0) {
				transientFailuresLeft--;
				throw new SocialUnlinkException("stubbed transient failure", null, true);
			}
			if (failing) {
				throw new SocialUnlinkException("stubbed failure", null, retryable);
			}
			tokens.add(accessToken);
		}
	}
}
