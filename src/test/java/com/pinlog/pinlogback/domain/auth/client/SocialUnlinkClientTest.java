package com.pinlog.pinlogback.domain.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * 공급자 연결 해제 호출(BD-48 §①).
 *
 * <p>세 공급자 모두 access token으로 해제할 수 있지만 <b>요청 형태가 다르다.</b> Kakao는 Bearer
 * 헤더, Google과 Naver는 form 본문이고 Naver는 클라이언트 인증까지 요구한다. 그 차이가 계약이므로
 * 요청을 그대로 고정한다.
 */
@DisplayName("소셜 연결 해제")
class SocialUnlinkClientTest {

	private static final String ACCESS_TOKEN = "provider-access-token";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	@Test
	@DisplayName("클라이언트마다 담당 공급자를 밝힌다 — 호출부가 이것으로 고른다")
	void eachClientDeclaresItsProvider() {
		assertThat(new KakaoUnlinkClient(builder.build()).provider()).isEqualTo(SocialProvider.KAKAO);
		assertThat(new GoogleUnlinkClient(builder.build()).provider()).isEqualTo(SocialProvider.GOOGLE);
		assertThat(new NaverUnlinkClient(builder.build(), registrationId -> null).provider())
			.isEqualTo(SocialProvider.NAVER);
	}

	@Nested
	@DisplayName("Kakao")
	class Kakao {

		private final KakaoUnlinkClient client = new KakaoUnlinkClient(builder.build());

		@Test
		@DisplayName("Bearer 헤더로 unlink를 호출한다")
		void unlinksWithBearerHeader() {
			server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
				.andRespond(withSuccess("{\"id\":123456789}", MediaType.APPLICATION_JSON));

			client.unlink(ACCESS_TOKEN);

			server.verify();
		}

		@Test
		@DisplayName("실패 응답은 예외로 올린다")
		void failureBecomesException() {
			// 삼키면 해제되지 않은 채 회원이 지워진다. 카카오 약관이 해제를 요구하므로 그건 위반이다.
			server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
				.andRespond(withServerError());

			assertThatThrownBy(() -> client.unlink(ACCESS_TOKEN))
				.isInstanceOf(SocialUnlinkException.class);
		}
	}

	@Nested
	@DisplayName("Google")
	class Google {

		private final GoogleUnlinkClient client = new GoogleUnlinkClient(builder.build());

		@Test
		@DisplayName("form 본문의 token 파라미터로 revoke를 호출한다")
		void revokesWithFormEncodedToken() {
			server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(HttpHeaders.CONTENT_TYPE,
					MediaType.APPLICATION_FORM_URLENCODED_VALUE))
				.andExpect(content().string("token=" + ACCESS_TOKEN))
				.andRespond(withSuccess());

			client.unlink(ACCESS_TOKEN);

			server.verify();
		}

		@Test
		@DisplayName("실패 응답은 예외로 올린다")
		void failureBecomesException() {
			server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST));

			assertThatThrownBy(() -> client.unlink(ACCESS_TOKEN))
				.isInstanceOf(SocialUnlinkException.class);
		}
	}

	@Nested
	@DisplayName("Naver")
	class Naver {

		private final NaverUnlinkClient client =
			new NaverUnlinkClient(builder.build(), clientRegistrations());

		@Test
		@DisplayName("클라이언트 자격증명과 함께 revoke를 호출한다")
		void revokesWithClientCredentials() {
			// Naver는 RFC 7009형 폐기라 client_id·client_secret 없이는 401이다.
			server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().string(
					"client_id=naver-client-id&client_secret=naver-client-secret"
						+ "&token=" + ACCESS_TOKEN + "&token_type_hint=access_token"))
				.andRespond(withSuccess());

			client.unlink(ACCESS_TOKEN);

			server.verify();
		}

		@Test
		@DisplayName("실패 응답은 예외로 올린다")
		void failureBecomesException() {
			server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

			assertThatThrownBy(() -> client.unlink(ACCESS_TOKEN))
				.isInstanceOf(SocialUnlinkException.class);
		}

		private ClientRegistrationRepository clientRegistrations() {
			return new InMemoryClientRegistrationRepository(
				ClientRegistration.withRegistrationId(SocialProvider.NAVER.registrationId())
					.clientId("naver-client-id")
					.clientSecret("naver-client-secret")
					.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
					.redirectUri("https://pinlog.example/api/core/v1/auth/naver/callback")
					.authorizationUri("https://nid.naver.com/oauth2.0/authorize")
					.tokenUri("https://nid.naver.com/oauth2.0/token")
					.build());
		}
	}
}
