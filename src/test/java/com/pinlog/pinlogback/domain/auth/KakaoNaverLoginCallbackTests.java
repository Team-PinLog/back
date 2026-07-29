package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Value("${local.server.port}")
	private int port;

	@ParameterizedTest(name = "{0}")
	@CsvSource({"kakao, KAKAO, 811001", "naver, NAVER, naver-id-new"})
	@DisplayName("신규 사용자는 콜백에서 회원과 소셜 계정이 함께 생성된다")
	void callbackCreatesMemberForNewUser(String registrationId, SocialProvider provider, String subject)
		throws Exception {
		long membersBefore = countMembers();

		HttpResponse<String> callback = completeLogin(port, registrationId, subject);

		assertThat(callback.statusCode()).isBetween(300, 399);
		assertThat(countMembers()).isEqualTo(membersBefore + 1);
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(provider, subject))
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
	@DisplayName("이메일이 없는 응답에서도 가입이 성공한다")
	void callbackSucceedsWithoutEmail(String registrationId, SocialProvider provider, String subject)
		throws Exception {
		// 공급자가 이메일을 주지 않거나 사용자가 동의하지 않으면 속성 자체가 없다(06 2.2).
		// 이 경로가 막히면 Kakao에서 가입이 통째로 실패한다 — 이메일 동의는 선택 항목이다.
		provider().useEmail(null);
		try {
			HttpResponse<String> callback = completeLogin(port, registrationId, subject);

			assertThat(callback.statusCode())
				.as("이메일 없음은 실패가 아니다. body=%s", callback.body())
				.isBetween(300, 399);
			assertThat(callback.headers().firstValue("Location"))
				.get().asString()
				.doesNotContain("error=");
			assertThat(socialAccountRepository.findByProviderAndProviderUserId(provider, subject))
				.isPresent();
		} finally {
			provider().useEmail(StubOAuthProvider.EMAIL);
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

	/** 상위 클래스의 static 필드를 가린 이름 없이 쓰기 위한 접근자. */
	private StubOAuthProvider provider() {
		return provider;
	}

	private long countMembers() {
		Long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.member WHERE deleted_at IS NULL", Long.class);
		return count == null ? 0 : count;
	}
}
