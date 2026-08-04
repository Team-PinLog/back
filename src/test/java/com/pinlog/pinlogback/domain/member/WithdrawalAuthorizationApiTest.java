package com.pinlog.pinlogback.domain.member;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.support.CoreApiFixtures;

/**
 * 탈퇴 왕복의 <b>진입</b>(BD-48 §②). 시작 요청이 만든 URL로 실제로 들어가 보는 것까지 본다 —
 * 두 계층이 같은 파라미터 이름과 같은 서명 규칙을 써야 하는데, 각자 단위 테스트만 있으면 한쪽만
 * 바꿔도 통과한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("탈퇴 인가 진입")
class WithdrawalAuthorizationApiTest extends CoreApiFixtures {

	private static final String WITHDRAWAL_PATH = "/v1/me";
	private static final String AUTHORIZE_PATH = "/v1/auth/authorize/google";

	@Test
	@DisplayName("시작 요청이 준 URL로 들어가면 공급자로 리다이렉트된다")
	void theIssuedUrlActuallyEntersTheProviderRoundTrip() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-entry-1", "a@example.com");

		MvcResult started = mockMvc.perform(delete(WITHDRAWAL_PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn();
		String authorizationUrl = parse(started.getResponse().getContentAsString())
			.get("data").get("authorizationUrl").asText();
		// context-path는 MockMvc가 붙이지 않는다.
		String pathWithQuery = authorizationUrl.substring(authorizationUrl.indexOf("/v1/"));

		mockMvc.perform(get(pathWithQuery))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string(HttpHeaders.LOCATION,
				org.hamcrest.Matchers.startsWith("https://accounts.google.com/")));
	}

	@Test
	@DisplayName("티켓이 없으면 평범한 로그인 진입이다")
	void withoutATicketItIsAPlainLoginEntry() throws Exception {
		mockMvc.perform(get(AUTHORIZE_PATH))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string(HttpHeaders.LOCATION,
				org.hamcrest.Matchers.startsWith("https://accounts.google.com/")));
	}

	@Test
	@DisplayName("티켓이 유효하지 않으면 이유를 담아 클라이언트로 돌려보낸다")
	void anInvalidTicketRedirectsBackInsteadOf404() throws Exception {
		// 404로 끝내면 사용자는 이유를 모른다. 여기 닿는 경우는 위조이거나 티켓이 만료된 것인데,
		// 후자는 SPA가 확인 단계를 끼우면 실제로 생긴다.
		mockMvc.perform(get(AUTHORIZE_PATH).param("ticket", "not-a-jwt"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string(HttpHeaders.LOCATION,
				org.hamcrest.Matchers.containsString("error=WITHDRAWAL_FAILED")));
	}

	@Test
	@DisplayName("소셜 계정이 여럿이면 시작하지 못한다")
	void refusesToStartWhenTheMemberHasMoreThanOneSocialAccount() throws Exception {
		// 한 번의 왕복은 한 공급자만 인가한다. 시작해 봐야 완료 단계에서 막히므로 여기서 끊는다.
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-entry-2", "b@example.com");
		givenSocialAccount(memberId, SocialProvider.KAKAO, "kakao-entry-2", "b@example.com");

		mockMvc.perform(delete(WITHDRAWAL_PATH).with(loginAs(memberId)))
			.andExpect(status().is5xxServerError());

		assertThat(deletedAtOf("core.member", memberId)).isNull();
	}
}
