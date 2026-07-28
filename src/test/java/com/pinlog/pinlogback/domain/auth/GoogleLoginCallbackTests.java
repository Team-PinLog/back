package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

/**
 * 로그인 진입부터 콜백 처리까지 한 흐름을 검증한다.
 *
 * <p>공급자는 {@link StubOAuthProvider}로 대역화한다. 실제 Google을 부르지 않으므로 CI에서도 돈다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "management.health.redis.enabled=false"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("소셜 로그인 콜백")
class GoogleLoginCallbackTests extends PostgresContainerSupport {

	private static StubOAuthProvider provider;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Value("${local.server.port}")
	private int port;

	@BeforeAll
	static void startProvider() throws IOException {
		provider = StubOAuthProvider.start();
	}

	@AfterAll
	static void stopProvider() {
		provider.stop();
	}

	@DynamicPropertySource
	static void stubProviderEndpoints(DynamicPropertyRegistry registry) {
		String base = "spring.security.oauth2.client.";
		registry.add(base + "registration.google.client-id", () -> "stub-client-id");
		registry.add(base + "registration.google.client-secret", () -> "stub-client-secret");
		// openid를 빼서 OIDC 검증(서명된 id_token·JWKS)을 우회한다. StubOAuthProvider 참고.
		registry.add(base + "registration.google.scope", () -> "email");
		registry.add(base + "provider.google.authorization-uri", () -> provider.baseUrl() + "/authorize");
		registry.add(base + "provider.google.token-uri", () -> provider.baseUrl() + "/token");
		registry.add(base + "provider.google.user-info-uri", () -> provider.baseUrl() + "/userinfo");
		registry.add(base + "provider.google.user-name-attribute", () -> "sub");
	}

	@Test
	@DisplayName("신규 사용자는 콜백에서 회원과 소셜 계정이 함께 생성된다")
	void callbackCreatesMemberForNewUser() throws Exception {
		String subject = provider.useSubject("google-sub-new-user");
		long membersBefore = countMembers();

		HttpClient client = newClient();
		HttpResponse<String> callback = callback(client, startLoginAndCaptureState(client));

		assertThat(callback.statusCode()).isBetween(300, 399);
		assertThat(countMembers()).isEqualTo(membersBefore + 1);
		assertThat(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, subject))
			.isPresent();
		assertThat(provider.tokenRequestCount()).isPositive();
	}

	@Test
	@DisplayName("콜백은 클라이언트 복귀 경로로 리다이렉트한다")
	void callbackRedirectsToClient() throws Exception {
		provider.useSubject("google-sub-redirect");

		HttpClient client = newClient();
		HttpResponse<String> callback = callback(client, startLoginAndCaptureState(client));

		assertThat(callback.headers().firstValue("Location"))
			.get()
			.asString()
			.contains("/auth/callback");
	}

	@Test
	@DisplayName("이미 가입한 소셜 계정이면 회원을 새로 만들지 않는다")
	void callbackReusesExistingMember() throws Exception {
		provider.useSubject("google-sub-returning");

		HttpClient first = newClient();
		callback(first, startLoginAndCaptureState(first));
		long membersAfterSignup = countMembers();

		HttpClient second = newClient();
		callback(second, startLoginAndCaptureState(second));

		assertThat(countMembers()).isEqualTo(membersAfterSignup);
	}

	@Test
	@DisplayName("인가 요청을 세션에 저장하지 않는다")
	void loginDoesNotCreateHttpSession() throws Exception {
		// STATELESS 선언과 어긋나지 않으려면 인가 요청이 쿠키에 담겨야 한다.
		// 기본 HttpSessionOAuth2AuthorizationRequestRepository는 여기서 JSESSIONID를 만든다.
		HttpClient client = newClient();
		startLoginAndCaptureState(client);

		assertThat(issuedCookieNames(client)).noneMatch(name -> name.equalsIgnoreCase("JSESSIONID"));
	}

	private HttpClient newClient() {
		return HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.cookieHandler(new CookieManager())
			.build();
	}

	/** 로그인 진입 → 인가 엔드포인트까지 따라가 공급자 URL의 state를 돌려준다. */
	private String startLoginAndCaptureState(HttpClient client) throws IOException, InterruptedException {
		String current = "/api/core/v1/auth/google/login";
		for (int hop = 0; hop < 3; hop++) {
			HttpResponse<String> response = get(client, current);
			String location = response.headers().firstValue("Location").orElseThrow();
			if (location.startsWith("http")) {
				MultiValueMap<String, String> params =
					UriComponentsBuilder.fromUriString(location).build().getQueryParams();
				return params.getFirst("state");
			}
			current = location;
		}
		throw new AssertionError("인가 요청 URL에 도달하지 못했다");
	}

	private HttpResponse<String> callback(HttpClient client, String state) throws IOException, InterruptedException {
		return get(client, "/api/core/v1/auth/google/callback?code=stub-code&state=" + state);
	}

	private HttpResponse<String> get(HttpClient client, String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.GET()
			.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
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
