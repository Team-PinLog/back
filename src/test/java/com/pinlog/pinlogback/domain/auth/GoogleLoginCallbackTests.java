package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;

/**
 * 로그인 진입부터 콜백 처리까지 한 흐름을 검증한다. 여기서 보는 것은 <b>회원 확정</b>이며,
 * 토큰·쿠키 계약은 {@link AuthTokenContractTests}가 본다.
 *
 * <p>공급자는 {@link StubOAuthProvider}로 대역화한다. 실제 Google을 부르지 않으므로 CI에서도 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("소셜 로그인 콜백")
class GoogleLoginCallbackTests extends SocialLoginTestSupport {

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Value("${local.server.port}")
	private int port;

	@Test
	@DisplayName("신규 사용자는 콜백에서 회원과 소셜 계정이 함께 생성된다")
	void callbackCreatesMemberForNewUser() throws Exception {
		String subject = provider.useSubject("google-sub-new-user");
		long membersBefore = countMembers();

		HttpClient client = newClient();
		var callback = callback(client, port, startLoginAndCaptureState(client, port));

		assertThat(callback.statusCode()).isBetween(300, 399);
		assertThat(countMembers()).isEqualTo(membersBefore + 1);
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, subject))
			.isPresent();
		assertThat(provider.tokenRequestCount()).isPositive();
	}

	@Test
	@DisplayName("콜백은 클라이언트 복귀 경로로 리다이렉트한다")
	void callbackRedirectsToClient() throws Exception {
		var callback = completeLogin(port, "google-sub-redirect");

		assertThat(callback.headers().firstValue("Location"))
			.get()
			.asString()
			.contains("/auth/callback");
	}

	@Test
	@DisplayName("이미 가입한 소셜 계정이면 회원을 새로 만들지 않는다")
	void callbackReusesExistingMember() throws Exception {
		completeLogin(port, "google-sub-returning");
		long membersAfterSignup = countMembers();

		completeLogin(port, "google-sub-returning");

		assertThat(countMembers()).isEqualTo(membersAfterSignup);
	}

	@Test
	@DisplayName("인가 요청을 세션에 저장하지 않는다")
	void loginDoesNotCreateHttpSession() throws Exception {
		// STATELESS 선언과 어긋나지 않으려면 인가 요청이 쿠키에 담겨야 한다.
		// 기본 HttpSessionOAuth2AuthorizationRequestRepository는 여기서 JSESSIONID를 만든다.
		HttpClient client = newClient();
		startLoginAndCaptureState(client, port);

		assertThat(issuedCookieNames(client)).noneMatch(name -> name.equalsIgnoreCase("JSESSIONID"));
	}

	private List<String> issuedCookieNames(HttpClient client) {
		CookieManager manager = (CookieManager)client.cookieHandler().orElseThrow();
		return manager.getCookieStore().getCookies().stream()
			.map(java.net.HttpCookie::getName)
			.toList();
	}

	private long countMembers() {
		Long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.member WHERE deleted_at IS NULL", Long.class);
		return count == null ? 0 : count;
	}
}
