package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * 소셜 로그인 진입이 공급자 인가 페이지로 넘기는지 확인한다.
 *
 * <p>실제 자격증명이 없어도 검증된다. 인가 요청 URL을 만드는 데는 client-id 문자열만 필요하고
 * 공급자와 통신하지 않기 때문이다. CI에서도 그대로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("소셜 로그인 진입")
class SocialLoginRedirectTests extends IntegrationContainerSupport {

	private final HttpClient httpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	@Value("${local.server.port}")
	private int port;

	@Test
	@DisplayName("Google 로그인 진입은 Google 인가 페이지로 리다이렉트한다")
	void googleLoginRedirectsToProvider() throws Exception {
		String location = followUntilProvider("/api/core/v1/auth/google/login");

		assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
		assertThat(location).contains("response_type=code");
		assertThat(location).contains("state=");
	}

	@Test
	@DisplayName("인가 요청의 redirect_uri가 명세 경로와 일치한다")
	void authorizationRequestCarriesSpecifiedRedirectUri() throws Exception {
		String location = URLDecoder.decode(
			followUntilProvider("/api/core/v1/auth/google/login"), StandardCharsets.UTF_8);

		// 공급자 콘솔에 등록한 값과 한 글자라도 다르면 redirect_uri_mismatch가 난다.
		assertThat(location).contains("/api/core/v1/auth/google/callback");
	}

	@Test
	@DisplayName("인가 요청에 PKCE code_challenge가 포함된다")
	void authorizationRequestUsesPkce() throws Exception {
		// RFC 9700이 Authorization Code 흐름에 PKCE를 요구한다.
		String location = followUntilProvider("/api/core/v1/auth/google/login");

		assertThat(location).contains("code_challenge=");
		assertThat(location).contains("code_challenge_method=S256");
	}

	@Test
	@DisplayName("지원하지 않는 provider는 404를 반환한다")
	void unknownProviderIsNotFound() throws Exception {
		HttpResponse<String> response = get("/api/core/v1/auth/unknown/login");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).contains("\"success\":false");
	}

	/** 진입 경로는 Spring 인가 엔드포인트를 한 번 거치므로 공급자 URL이 나올 때까지 따라간다. */
	private String followUntilProvider(String path) throws IOException, InterruptedException {
		String current = path;
		for (int hop = 0; hop < 3; hop++) {
			HttpResponse<String> response = get(current);
			assertThat(response.statusCode())
				.as("리다이렉트를 기대했다: %s", current)
				.isBetween(300, 399);
			String location = response.headers().firstValue("Location").orElseThrow();
			if (location.startsWith("http")) {
				return location;
			}
			current = location;
		}
		throw new AssertionError("공급자 인가 URL에 도달하지 못했다");
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.GET()
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
