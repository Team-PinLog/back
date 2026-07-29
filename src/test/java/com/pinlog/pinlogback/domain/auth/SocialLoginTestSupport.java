package com.pinlog.pinlogback.domain.auth;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 소셜 로그인 흐름을 실제로 한 번 돌아야 하는 테스트의 기반.
 *
 * <p>공급자 대역({@link StubOAuthProvider}) 기동과 설정 덮어쓰기, 로그인 진입 → 인가 →
 * 콜백까지 리다이렉트를 따라가는 절차를 모은다. 이 절차가 테스트마다 복제되면 콜백 경로나
 * state 전달 방식이 바뀔 때 고쳐야 할 곳이 늘어난다.
 *
 * <p>로그인 성공이 Refresh를 Redis에 저장하므로 콜백을 타려면 Redis가 떠 있어야 한다.
 * {@link IntegrationContainerSupport}가 항상 띄우므로 여기서 따로 챙길 것은 없다.
 */
public abstract class SocialLoginTestSupport extends IntegrationContainerSupport {

	protected static StubOAuthProvider provider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 콜백을 타는 테스트는 대부분 자기 포트로 요청을 만들어야 한다. */
	@Value("${local.server.port}")
	protected int port;

	/**
	 * 활성 회원 수. 신규 가입인지 기존 회원 재사용인지 가르는 기준이라 콜백 테스트마다 필요하다.
	 *
	 * <p>{@code deleted_at IS NULL} 조건을 여기 한 곳에만 둔다 — 복제하면 소프트 삭제 규칙이
	 * 바뀔 때 고쳐야 할 곳이 늘어난다.
	 */
	protected long countMembers() {
		Long count = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.member WHERE deleted_at IS NULL", Long.class);
		return count == null ? 0 : count;
	}

	@BeforeAll
	static void startStubProvider() throws IOException {
		provider = StubOAuthProvider.start();
	}

	@AfterAll
	static void stopStubProvider() {
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

		// Kakao·Naver도 같은 대역을 쓰되 userinfo 경로만 갈라 각자의 응답 형태를 받는다.
		// user-name-attribute는 운영 설정과 같은 값을 둔다 — 이 키가 틀리면 Spring이
		// DefaultOAuth2User 생성에서 먼저 죽고 우리 정규화까지 오지 않는다.
		for (String registrationId : new String[] {"kakao", "naver"}) {
			registry.add(base + "registration." + registrationId + ".client-id", () -> "stub-client-id");
			registry.add(base + "registration." + registrationId + ".client-secret", () -> "stub-client-secret");
			registry.add(base + "provider." + registrationId + ".authorization-uri",
				() -> provider.baseUrl() + "/authorize");
			registry.add(base + "provider." + registrationId + ".token-uri", () -> provider.baseUrl() + "/token");
			registry.add(base + "provider." + registrationId + ".user-info-uri",
				() -> provider.baseUrl() + "/userinfo/" + registrationId);
		}
	}

	/** 쿠키를 보관하고 리다이렉트를 따라가지 않는 클라이언트. 인가 요청 URL을 직접 봐야 한다. */
	protected HttpClient newClient() {
		return HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.cookieHandler(new CookieManager())
			.build();
	}

	/** 로그인 진입 → 공급자 → 콜백까지 한 흐름을 돌고 콜백 응답을 돌려준다. */
	protected HttpResponse<String> completeLogin(int port, String subject)
		throws IOException, InterruptedException {
		return completeLogin(port, "google", subject);
	}

	/** 공급자를 지목해 같은 흐름을 돈다. */
	protected HttpResponse<String> completeLogin(int port, String registrationId, String subject)
		throws IOException, InterruptedException {
		provider.useSubject(subject);
		HttpClient client = newClient();
		return callback(client, port, registrationId, startLoginAndCaptureState(client, port, registrationId));
	}

	/** 로그인 진입 → 인가 엔드포인트까지 따라가 공급자 URL의 state를 돌려준다. */
	protected String startLoginAndCaptureState(HttpClient client, int port)
		throws IOException, InterruptedException {
		return startLoginAndCaptureState(client, port, "google");
	}

	protected String startLoginAndCaptureState(HttpClient client, int port, String registrationId)
		throws IOException, InterruptedException {
		String current = "/api/core/v1/auth/" + registrationId + "/login";
		for (int hop = 0; hop < 3; hop++) {
			HttpResponse<String> response = get(client, port, current);
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

	protected HttpResponse<String> callback(HttpClient client, int port, String state)
		throws IOException, InterruptedException {
		return callback(client, port, "google", state);
	}

	protected HttpResponse<String> callback(HttpClient client, int port, String registrationId, String state)
		throws IOException, InterruptedException {
		return get(client, port,
			"/api/core/v1/auth/" + registrationId + "/callback?code=stub-code&state=" + state);
	}

	protected HttpResponse<String> get(HttpClient client, int port, String path)
		throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder().uri(uri(port, path)).GET().build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	protected URI uri(int port, String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
