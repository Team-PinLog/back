package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.nimbusds.jwt.SignedJWT;

/**
 * 토큰 발급·검증·회전 계약을 HTTP 경계에서 검증한다(인증 PR 계약 5, BD-21·BD-31).
 *
 * <p>쿠키를 {@code CookieManager}에 맡기지 않고 {@code Set-Cookie} 헤더를 직접 읽는다. 두 가지
 * 이유가 있다. 첫째, 검증 대상이 <b>속성 자체</b>(HttpOnly·Secure·SameSite·Path)라서 파싱된
 * 쿠키가 아니라 원문을 봐야 한다. 둘째, {@code CookieManager}는 http 링크에 {@code Secure}
 * 쿠키를 싣지 않으므로 왕복 검증이 불가능해진다 — 실제 브라우저는 localhost를 신뢰할 수 있는
 * 오리진으로 취급해 http에서도 보내지만, Java 클라이언트는 그렇지 않다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("인증 토큰 계약")
class AuthTokenContractTests extends SocialLoginTestSupport {

	private static final String ACCESS_COOKIE = "access_token";
	private static final String REFRESH_COOKIE = "refresh_token";
	private static final String LOGGED_IN_COOKIE = "logged_in";
	private static final String CSRF_COOKIE = "XSRF-TOKEN";
	private static final String PROTECTED_PATH = "/api/core/v1/test-authenticated/me";

	@Test
	@DisplayName("콜백이 Access·Refresh·logged_in 쿠키를 계약된 속성으로 내려준다")
	void callbackIssuesAuthCookiesWithContractedAttributes() throws Exception {
		HttpResponse<String> callback = login("cookie-attributes");

		String access = setCookie(callback, ACCESS_COOKIE);
		assertThat(access).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
		// context path 전체에 실려야 한다. 더 좁히면 일반 API 요청에 Access가 빠진다.
		assertThat(attribute(access, "Path")).isEqualTo("/api/core");

		String refresh = setCookie(callback, REFRESH_COOKIE);
		assertThat(refresh).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
		// 일반 API 요청에 Refresh가 실리지 않도록 범위를 좁힌다(BD-21).
		assertThat(attribute(refresh, "Path")).isEqualTo("/api/core/v1/auth");

		// UI 힌트 전용이라 JS가 읽어야 한다. HttpOnly면 존재 이유가 없다.
		String loggedIn = setCookie(callback, LOGGED_IN_COOKIE);
		assertThat(loggedIn).doesNotContain("HttpOnly");
		assertThat(loggedIn).contains("Secure").contains("SameSite=Lax");
		// Path가 API 경로면 프론트 페이지(/, /auth/callback, /feed …)에서 document.cookie에
		// 나타나지 않아 읽을 방법이 없다. 실제 브라우저 로그인에서 확인된 문제다.
		assertThat(attribute(loggedIn, "Path"))
			.as("프론트는 루트 아래에서 실행된다. API 경로로 좁히면 이 쿠키는 쓸모가 없다")
			.isEqualTo("/");
	}

	@Test
	@DisplayName("XSRF-TOKEN 쿠키를 프론트 페이지에서 읽을 수 있다")
	void csrfCookieIsReadableFromClientPages() throws Exception {
		// logged_in과 같은 이유다(BT-04). 프론트가 document.cookie로 읽어 X-XSRF-TOKEN 헤더에
		// 넣어야 하는데, Path가 API 경로면 루트 아래 페이지에서 보이지 않아 값을 구할 방법이 없다.
		// 그러면 상태 변경 요청이 전부 403이 된다.
		//
		// 이 테스트가 Set-Cookie 원문을 보는 것이 핵심이다. postWithCsrf는 헤더에서 값을 직접
		// 꺼내 쓰므로 브라우저의 Path 제한을 우회한다 — 그래서 정상 경로 테스트가 통과해도
		// 실제 브라우저에서는 깨질 수 있었다.
		HttpResponse<String> response = send(
			HttpRequest.newBuilder().uri(uri(port, "/api/core/actuator/health")).GET().build());

		String csrf = setCookie(response, CSRF_COOKIE);
		assertThat(attribute(csrf, "Path"))
			.as("프론트가 읽어야 한다. API 경로로 좁히면 헤더에 넣을 값을 구할 수 없다")
			.isEqualTo("/");
		assertThat(csrf)
			.as("JS가 읽어야 하므로 HttpOnly면 안 된다")
			.doesNotContain("HttpOnly");
		// 기본값은 request.isSecure()를 따라간다. 그러면 이 속성이 프록시의 X-Forwarded-Proto가
		// 정확히 오는지에 걸린다 — 보안 속성을 인프라 설정에 맡기지 않는다(AuthCookies와 같은 판단).
		assertThat(csrf).contains("Secure").contains("SameSite=Lax");
	}

	@Test
	@DisplayName("콜백 처리 중 예외가 나도 500이 아니라 실패 복귀 경로로 돌아간다")
	void callbackFailureRedirectsInsteadOfServerError() throws Exception {
		// 성공 핸들러는 필터 체인 안에서 돌아 @RestControllerAdvice를 타지 않는다. 감싸지 않으면
		// 공통 envelope도 명세가 정한 복귀도 아닌 컨테이너 기본 500 페이지가 나간다.
		//
		// 트리거는 컬럼 상한(email VARCHAR(255))을 넘기는 값이다. 저장 실패가 성공 핸들러 안에서
		// 터지므로 우리가 감싼 경로를 정확히 탄다.
		provider.useEmail("x".repeat(300) + "@example.com");
		try {
			HttpResponse<String> callback = completeLogin(port, "google-sub-callback-failure");

			assertThat(callback.statusCode())
				.as("500이 아니라 리다이렉트여야 한다. body=%s", callback.body())
				.isBetween(300, 399);
			assertThat(callback.headers().firstValue("Location"))
				.get().asString()
				.contains("/auth/callback")
				.contains("error=OAUTH_FAILED");
			assertThat(callback.headers().allValues("Set-Cookie"))
				.as("실패했으므로 인증 쿠키가 나가면 안 된다")
				.noneMatch(header -> header.startsWith(ACCESS_COOKIE + "=")
					|| header.startsWith(REFRESH_COOKIE + "="));
		} finally {
			provider.useEmail(StubOAuthProvider.EMAIL);
		}
	}

	@Test
	@DisplayName("콜백 응답 본문에 토큰 문자열이 없다")
	void callbackResponseBodyCarriesNoToken() throws Exception {
		// 쿠키를 택한 이유가 여기 있으므로 회귀로 고정한다(BD-21, 인증 PR 계약 5).
		HttpResponse<String> callback = login("no-token-in-body");

		assertThat(callback.body()).doesNotContain(cookieValue(callback, ACCESS_COOKIE));
		assertThat(callback.body()).doesNotContain(cookieValue(callback, REFRESH_COOKIE));
	}

	@Test
	@DisplayName("유효한 Access 쿠키로 보호 경로를 요청하면 인증 주체까지 전달된다")
	void validAccessCookieCarriesThePrincipal() throws Exception {
		// 쿠키 → 필터 → SecurityContext → @LoginMember로 이어지는 principal 계약 전체를 한 번에 본다.
		// 200만 보면 리졸버가 엉뚱한 회원을 넣어도 통과한다.
		String accessToken = cookieValue(login("valid-access"), ACCESS_COOKIE);

		HttpResponse<String> response = getWithCookies(PROTECTED_PATH, ACCESS_COOKIE + "=" + accessToken);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"memberId\":" + subjectOf(accessToken));
	}

	@Test
	@DisplayName("Access 쿠키가 없으면 401이다")
	void missingAccessCookieIsUnauthorized() throws Exception {
		HttpResponse<String> response = getWithCookies(PROTECTED_PATH, null);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("\"code\":\"UNAUTHORIZED\"");
	}

	@Test
	@DisplayName("서명이 조작된 Access 쿠키는 401이다")
	void tamperedAccessCookieIsUnauthorized() throws Exception {
		String accessToken = cookieValue(login("tampered-access"), ACCESS_COOKIE);
		String tampered = accessToken.substring(0, accessToken.length() - 4) + "AAAA";

		HttpResponse<String> response = getWithCookies(PROTECTED_PATH, ACCESS_COOKIE + "=" + tampered);

		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("Refresh는 새 토큰 쌍을 발급하고 회전 전 토큰을 무효화한다")
	void refreshRotatesAndInvalidatesPreviousToken() throws Exception {
		String firstRefresh = cookieValue(login("refresh-rotation"), REFRESH_COOKIE);

		HttpResponse<String> rotated = postWithCsrf("/api/core/v1/auth/refresh", firstRefresh);

		assertThat(rotated.statusCode()).isEqualTo(204);
		assertThat(rotated.body()).isEmpty();
		String secondRefresh = cookieValue(rotated, REFRESH_COOKIE);
		assertThat(secondRefresh).isNotEqualTo(firstRefresh);
		assertThat(cookieValue(rotated, ACCESS_COOKIE)).isNotBlank();

		// 회전 후 토큰은 계속 유효하다 — 회전이 사슬로 이어진다. 재사용 검사보다 앞에 둔다:
		// 재사용이 감지되면 이 토큰까지 폐기되므로(BD-35) 뒤에서는 확인할 수 없다.
		assertThat(postWithCsrf("/api/core/v1/auth/refresh", secondRefresh).statusCode()).isEqualTo(204);

		// 회전 전 Refresh 재사용은 401이다(인증 PR 계약 5).
		assertThat(postWithCsrf("/api/core/v1/auth/refresh", firstRefresh).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("Refresh 재사용이 감지되면 그 회원의 다른 세션도 전부 끊긴다")
	void reuseDetectionRevokesEveryRefreshOfTheMember() throws Exception {
		// 유출 시나리오: 공격자가 먼저 회전하면 정상 사용자만 401로 끊기고 공격자가 방금 받은
		// 토큰은 유효하다. 그래서 재사용을 유출 신호로 보고 전부 폐기한다(RFC 9700 §4.14.2, BD-35).
		String subject = "reuse-revokes-family";
		HttpResponse<String> deviceALogin = login(subject);
		HttpResponse<String> deviceBLogin = login(subject);

		// 전제를 단언으로 만든다. 두 로그인이 서로 다른 회원이면 아래는 "다른 회원까지 끊는다"는
		// 정반대 사실을 통과시킨다.
		assertThat(subjectOf(cookieValue(deviceALogin, ACCESS_COOKIE)))
			.as("같은 subject로 로그인했으면 같은 회원이어야 한다")
			.isEqualTo(subjectOf(cookieValue(deviceBLogin, ACCESS_COOKIE)));

		String deviceARefresh = cookieValue(deviceALogin, REFRESH_COOKIE);
		String deviceBRefresh = cookieValue(deviceBLogin, REFRESH_COOKIE);
		assertThat(deviceARefresh).isNotEqualTo(deviceBRefresh);

		// 공격자가 먼저 회전한 상태를 만든다.
		HttpResponse<String> rotated = postWithCsrf("/api/core/v1/auth/refresh", deviceARefresh);
		assertThat(rotated.statusCode()).isEqualTo(204);
		String rotatedRefresh = cookieValue(rotated, REFRESH_COOKIE);

		// 소비된 토큰이 다시 들어온다 = 재사용 감지.
		assertThat(postWithCsrf("/api/core/v1/auth/refresh", deviceARefresh).statusCode()).isEqualTo(401);

		assertThat(postWithCsrf("/api/core/v1/auth/refresh", deviceBRefresh).statusCode())
			.as("재사용이 감지됐으면 다른 기기의 Refresh도 끊겨야 한다")
			.isEqualTo(401);
		assertThat(postWithCsrf("/api/core/v1/auth/refresh", rotatedRefresh).statusCode())
			.as("회전으로 갓 발급된 토큰도 폐기 대상이다 — 공격자가 들고 있을 토큰이다")
			.isEqualTo(401);
	}

	@Test
	@DisplayName("정상 회전은 다른 기기의 세션을 끊지 않는다")
	void normalRotationKeepsOtherSessionsAlive() throws Exception {
		// 폐기가 재사용 감지 밖으로 새는 것을 잡는다. 새면 세션 독립성(BD-21)이 조용히 깨진다.
		String subject = "normal-rotation-keeps-sessions";
		String deviceARefresh = cookieValue(login(subject), REFRESH_COOKIE);
		String deviceBRefresh = cookieValue(login(subject), REFRESH_COOKIE);

		assertThat(postWithCsrf("/api/core/v1/auth/refresh", deviceARefresh).statusCode()).isEqualTo(204);

		assertThat(postWithCsrf("/api/core/v1/auth/refresh", deviceBRefresh).statusCode())
			.as("재사용이 아닌 정상 회전이었다. 다른 기기는 그대로 살아 있어야 한다")
			.isEqualTo(204);
	}

	@Test
	@DisplayName("로그아웃 후 같은 Refresh 쿠키로 재발급하면 401이다")
	void refreshAfterLogoutIsUnauthorized() throws Exception {
		String refreshToken = cookieValue(login("logout-invalidates"), REFRESH_COOKIE);

		assertThat(postWithCsrf("/api/core/v1/auth/logout", refreshToken).statusCode()).isEqualTo(204);
		assertThat(postWithCsrf("/api/core/v1/auth/refresh", refreshToken).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("로그아웃은 인증 쿠키를 만료시킨다")
	void logoutExpiresAuthCookies() throws Exception {
		String refreshToken = cookieValue(login("logout-clears-cookies"), REFRESH_COOKIE);

		HttpResponse<String> logout = postWithCsrf("/api/core/v1/auth/logout", refreshToken);

		assertThat(setCookie(logout, ACCESS_COOKIE)).contains("Max-Age=0");
		assertThat(setCookie(logout, REFRESH_COOKIE)).contains("Max-Age=0");
		assertThat(setCookie(logout, LOGGED_IN_COOKIE)).contains("Max-Age=0");
	}

	@Test
	@DisplayName("재발급이 401이면 인증 쿠키를 만료시킨다")
	void failedRefreshExpiresAuthCookies() throws Exception {
		// 실패 경로가 쿠키를 남기면 클라이언트는 죽은 Refresh를 계속 보낸다. 그때마다 재사용으로
		// 판정돼 폐기가 다시 도므로, 그 사이 새로 로그인한 세션까지 끊긴다. logged_in도 7일짜리로
		// 남아 UI는 계속 로그인 상태를 가리킨다 — 빠져나갈 상태 전이가 없어진다.
		//
		// 로그아웃과 같은 정리를 하지만 의미는 다르다. 로그아웃은 사용자가 끝낸 것이고, 이쪽은
		// 서버가 세션이 끝났음을 통보하는 것이다. 클라이언트가 할 일은 같으므로 결과도 같다.
		String refreshToken = cookieValue(login("failed-refresh-clears-cookies"), REFRESH_COOKIE);
		assertThat(postWithCsrf("/api/core/v1/auth/logout", refreshToken).statusCode()).isEqualTo(204);

		HttpResponse<String> failed = postWithCsrf("/api/core/v1/auth/refresh", refreshToken);

		assertThat(failed.statusCode()).isEqualTo(401);
		assertThat(setCookie(failed, ACCESS_COOKIE)).contains("Max-Age=0");
		assertThat(setCookie(failed, REFRESH_COOKIE)).contains("Max-Age=0");
		assertThat(setCookie(failed, LOGGED_IN_COOKIE)).contains("Max-Age=0");
	}

	@Test
	@DisplayName("Access 쿠키를 Refresh 자리에 넣어도 재발급되지 않는다")
	void accessTokenIsNotAcceptedAsRefreshToken() throws Exception {
		// 두 토큰의 용도를 구분하지 않으면 수명 30분짜리가 7일짜리 권한을 갖는다.
		String accessToken = cookieValue(login("token-use-separation"), ACCESS_COOKIE);

		assertThat(postWithCsrf("/api/core/v1/auth/refresh", accessToken).statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> login(String subject) throws IOException, InterruptedException {
		return completeLogin(port, "google-sub-" + subject);
	}

	private HttpResponse<String> getWithCookies(String path, String cookieHeader)
		throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder().uri(uri(port, path)).GET();
		if (cookieHeader != null) {
			request.header("Cookie", cookieHeader);
		}
		return send(request.build());
	}

	/**
	 * CSRF 토큰을 먼저 확보한 뒤 상태 변경 요청을 보낸다. 토큰 없이 보내면 403이므로
	 * (그 계약은 {@code SecurityContractTests}가 지킨다) 정상 경로 검증에는 필수다.
	 */
	private HttpResponse<String> postWithCsrf(String path, String refreshToken)
		throws IOException, InterruptedException {
		String csrfToken = cookieValue(
			send(HttpRequest.newBuilder().uri(uri(port, "/api/core/actuator/health")).GET().build()),
			CSRF_COOKIE);

		return send(HttpRequest.newBuilder()
			.uri(uri(port, path))
			.header("Cookie", REFRESH_COOKIE + "=" + refreshToken + "; " + CSRF_COOKIE + "=" + csrfToken)
			.header("X-XSRF-TOKEN", csrfToken)
			.POST(HttpRequest.BodyPublishers.noBody())
			.build());
	}

	/** 쿠키를 보관하지 않는다. 각 요청이 무엇을 들고 가는지 테스트가 직접 정해야 한다. */
	private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
		return HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.build()
			.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** 토큰의 {@code sub} 클레임. 서버가 어느 회원으로 발급했는지를 테스트가 알아야 한다. */
	private String subjectOf(String jwt) throws Exception {
		return SignedJWT.parse(jwt).getJWTClaimsSet().getSubject();
	}

	private String setCookie(HttpResponse<String> response, String name) {
		List<String> headers = response.headers().allValues("Set-Cookie");
		Optional<String> found = headers.stream().filter(header -> header.startsWith(name + "=")).findFirst();
		assertThat(found).as("%s 쿠키가 내려오지 않았다. 받은 Set-Cookie: %s", name, headers).isPresent();
		return found.orElseThrow();
	}

	private String cookieValue(HttpResponse<String> response, String name) {
		return attribute(setCookie(response, name), name);
	}

	/**
	 * {@code Set-Cookie} 원문에서 이름=값 하나를 꺼낸다. 쿠키 값과 속성이 같은 문법이라 같은
	 * 함수로 처리한다. {@code Path=/api/core}가 접두사로만 맞는 것을 통과시키지 않으려면
	 * 값 전체를 비교해야 하므로, 부분 문자열 검사 대신 이걸 쓴다.
	 */
	private String attribute(String setCookieHeader, String name) {
		for (String part : setCookieHeader.split(";")) {
			String trimmed = part.trim();
			if (trimmed.regionMatches(true, 0, name + "=", 0, name.length() + 1)) {
				return trimmed.substring(name.length() + 1);
			}
		}
		throw new AssertionError(name + " 속성이 없다: " + setCookieHeader);
	}
}
