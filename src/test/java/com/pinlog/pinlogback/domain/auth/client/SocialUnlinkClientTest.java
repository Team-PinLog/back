package com.pinlog.pinlogback.domain.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

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

import tools.jackson.databind.json.JsonMapper;

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
	@DisplayName("일시적 장애와 영구 실패를 구분해 올린다")
	void distinguishesTransientFailureFromPermanentOne() {
		// 구분하지 않으면 재시도가 무의미한 실패까지 되풀이해 사용자를 기다리게 하고, 반대로
		// 뭉뚱그려 포기하면 순간적 장애 하나가 탈퇴를 영구히 막는다.
		GoogleUnlinkClient client = new GoogleUnlinkClient(builder.build());

		server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		assertThat(catchThrowableOfType(SocialUnlinkException.class, () -> client.unlink(ACCESS_TOKEN)))
			.as("RFC 7009이 503에 재시도를 규범으로 둔다")
			.returns(true, SocialUnlinkException::isRetryable);

		server.reset();
		server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST));
		assertThat(catchThrowableOfType(SocialUnlinkException.class, () -> client.unlink(ACCESS_TOKEN)))
			.as("요청이 잘못됐거나 자격증명이 틀린 것은 되풀이해도 같다")
			.returns(false, SocialUnlinkException::isRetryable);
	}

	@Test
	@DisplayName("응답이 오지 않는 것도 일시적 장애로 본다")
	void ioFailureIsTransient() {
		// 타임아웃·연결 끊김은 공급자가 상태를 알려 주지 못한 것이라 해제 여부를 알 수 없다.
		// 재시도가 흡수하는 것은 요청이 닿지 못한 실패다. 닿아서 해제까지 됐는데 응답만 유실된
		// 경우는 다음 시도가 확정적 4xx로 끝난다 — Google이 그렇다.
		GoogleUnlinkClient client = new GoogleUnlinkClient(builder.build());
		server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
			.andRespond(request -> {
				throw new IOException("연결이 끊겼다");
			});

		assertThat(catchThrowableOfType(SocialUnlinkException.class, () -> client.unlink(ACCESS_TOKEN)))
			.returns(true, SocialUnlinkException::isRetryable);
	}

	@Test
	@DisplayName("클라이언트마다 담당 공급자를 밝힌다 — 호출부가 이것으로 고른다")
	void eachClientDeclaresItsProvider() {
		assertThat(new KakaoUnlinkClient(builder.build()).provider()).isEqualTo(SocialProvider.KAKAO);
		assertThat(new GoogleUnlinkClient(builder.build()).provider()).isEqualTo(SocialProvider.GOOGLE);
		NaverUnlinkClient naver =
			new NaverUnlinkClient(builder.build(), registrationId -> null, JsonMapper.builder().build());
		assertThat(naver.provider()).isEqualTo(SocialProvider.NAVER);
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
			new NaverUnlinkClient(builder.build(), clientRegistrations(), JsonMapper.builder().build());

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

		@Test
		@DisplayName("200이어도 본문에 error가 있으면 실패다")
		void errorInTheBodyIsFailureEvenOnSuccessStatus() {
			// Naver의 연동 해제 계열은 실패를 200 본문의 error로 알리는 형태가 보고돼 있다.
			// 상태 코드만 보면 해제 실패가 성공으로 읽혀 회원이 지워지고, 마스킹 때문에 그 뒤엔
			// 영구히 못 끊는다 — 이 PR이 막으려는 상태를 Naver 경로에서만 만들게 된다.
			server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
				.andRespond(withSuccess("{\"error\":\"024\",\"error_description\":\"Authentication failed\"}",
					MediaType.APPLICATION_JSON));

			assertThat(catchThrowableOfType(SocialUnlinkException.class, () -> client.unlink(ACCESS_TOKEN)))
				.as("되풀이해도 같은 실패다")
				.returns(false, SocialUnlinkException::isRetryable);
		}

		@Test
		@DisplayName("200에 result=success 본문이 와도 성공이다")
		void resultSuccessBodyPassesThrough() {
			// 엔드포인트가 grant_type=delete 쪽으로 확정되면 이 형태가 온다. 어느 쪽이든 통과해야
			// 한다 — 판정 기준은 "error가 있는가"이지 "본문이 비었는가"가 아니다.
			server.expect(requestTo("https://nid.naver.com/oauth2.0/revoke"))
				.andRespond(withSuccess("{\"access_token\":\"...\",\"result\":\"success\"}",
					MediaType.APPLICATION_JSON));

			client.unlink(ACCESS_TOKEN);

			server.verify();
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
