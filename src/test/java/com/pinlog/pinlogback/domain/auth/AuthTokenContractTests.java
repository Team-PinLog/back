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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.nimbusds.jwt.SignedJWT;

/**
 * 토큰 발급·검증·회전 계약을 HTTP 경계에서 검증한다(인증 PR 계약 5, BD-21·BD-29).
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

	@Value("${local.server.port}")
	private int port;

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

		// 회전 전 Refresh 재사용은 401이다(인증 PR 계약 5).
		assertThat(postWithCsrf("/api/core/v1/auth/refresh", firstRefresh).statusCode()).isEqualTo(401);
		// 회전 후 토큰은 계속 유효하다.
		assertThat(postWithCsrf("/api/core/v1/auth/refresh", secondRefresh).statusCode()).isEqualTo(204);
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
