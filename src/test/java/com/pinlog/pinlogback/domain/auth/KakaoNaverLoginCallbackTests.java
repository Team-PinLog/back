package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;

/**
 * Kakao·Naver 콜백이 Google과 같은 결과에 도달하는지 확인한다(S15P11A705-64).
 *
 * <p>두 공급자가 새로 들이는 것은 <b>응답 형태</b>뿐이다 — Kakao는 이메일을 {@code kakao_account}
 * 아래에 두고 식별자를 숫자로 주며, Naver는 본문을 {@code response}로 한 겹 감싼다. 토큰 발급·쿠키·
 * 회원 확정은 공급자와 무관한 경로라 {@link AuthTokenContractTests}·{@link GoogleLoginCallbackTests}가
 * 이미 본다. 그래서 여기서는 <b>정규화가 그 경로에 올바른 값을 넘기는지</b>만 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Kakao·Naver 로그인 콜백")
class KakaoNaverLoginCallbackTests extends SocialLoginTestSupport {

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@ParameterizedTest(name = "{0}")
	@CsvSource({"kakao, KAKAO, 811001", "naver, NAVER, naver-id-new"})
	@DisplayName("신규 사용자는 콜백에서 회원과 소셜 계정이 함께 생성된다")
	void callbackCreatesMemberForNewUser(String registrationId, SocialProvider expected, String subject)
		throws Exception {
		long membersBefore = countMembers();

		HttpResponse<String> callback = completeLogin(port, registrationId, subject);

		assertThat(callback.statusCode()).isBetween(300, 399);
		assertThat(countMembers()).isEqualTo(membersBefore + 1);
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(expected, subject))
			.as("식별자는 공급자가 준 값을 문자열로 그대로 저장해야 한다")
			.isPresent();
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({"kakao, 811002", "naver, naver-id-returning"})
	@DisplayName("이미 가입한 소셜 계정이면 회원을 새로 만들지 않는다")
	void callbackReusesExistingMember(String registrationId, String subject) throws Exception {
		completeLogin(port, registrationId, subject);
		long membersAfterSignup = countMembers();

		completeLogin(port, registrationId, subject);

		assertThat(countMembers()).isEqualTo(membersAfterSignup);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({"kakao, KAKAO, 811003", "naver, NAVER, naver-id-no-email"})
	@DisplayName("이메일이 없는 응답이면 가입하지 않고 실패로 돌아간다")
	void callbackWithoutEmailDoesNotSignUp(String registrationId, SocialProvider expected, String subject)
		throws Exception {
		// 이메일은 필수다(06 §2.2). 설정 화면이 이 값을 반드시 표시해야 하므로 값 없는 계정을
		// 두지 않는다. 공급자 콘솔이 이메일을 필수 동의로 두고 있어 사용자가 이 경로를 밟지는
		// 않지만, 그 설정이 깨졌을 때 값 없는 계정이 조용히 만들어지는 것을 여기서 막는다.
		long membersBefore = countMembers();
		provider.useEmail(null);
		try {
			HttpResponse<String> callback = completeLogin(port, registrationId, subject);

			assertThat(callback.statusCode())
				.as("실패도 리다이렉트로 돌아간다. body=%s", callback.body())
				.isBetween(300, 399);
			assertThat(callback.headers().firstValue("Location"))
				.as("사유별로 error 값을 가르지 않는다(08 §3.2)")
				.get().asString()
				.contains("error=OAUTH_FAILED");
			assertThat(callback.headers().allValues("Set-Cookie"))
				.as("가입하지 않았으므로 인증 쿠키가 나가면 안 된다")
				.noneMatch(header -> header.startsWith("access_token=")
					|| header.startsWith("refresh_token="));
			assertThat(socialAccountRepository.findByProviderAndProviderUserId(expected, subject))
				.as("값 없는 계정을 남기지 않는다")
				.isEmpty();
			assertThat(countMembers())
				.as("member도 만들어지지 않는다")
				.isEqualTo(membersBefore);
		} finally {
			provider.useEmail(StubOAuthProvider.EMAIL);
		}
	}

	@Test
	@DisplayName("공급자가 다르면 같은 식별자라도 별도 소셜 계정이 된다")
	void sameSubjectOnDifferentProvidersStaysSeparate() throws Exception {
		// social_account의 부분 유니크는 (provider, provider_user_id)다. provider를 빼고 잡으면
		// 두 번째 로그인이 남의 계정으로 붙는다 — 계정 탈취다.
		String shared = "811004";

		completeLogin(port, "kakao", shared);
		completeLogin(port, "naver", shared);

		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, shared))
			.isPresent();
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.NAVER, shared))
			.isPresent();
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, shared)
			.orElseThrow().getId())
			.isNotEqualTo(socialAccountRepository
				.findByProviderAndProviderUserId(SocialProvider.NAVER, shared).orElseThrow().getId());
	}

}
