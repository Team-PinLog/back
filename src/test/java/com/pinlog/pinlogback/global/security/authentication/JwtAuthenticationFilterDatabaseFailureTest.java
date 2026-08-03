package com.pinlog.pinlogback.global.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.global.exception.ErrorCode;
import com.pinlog.pinlogback.global.security.error.SecurityErrorWriter;
import com.pinlog.pinlogback.global.security.token.AuthCookies;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * 탈퇴 판정이 DB를 읽다 실패했을 때 무엇을 내보내는지 고정한다(BD-47).
 *
 * <p>BD-41이 이 필터에 {@code isActive}를 넣으면서 <b>순수 JWT 검증이던 필터가 처음으로 인프라
 * 사유로 던질 수 있게 됐다.</b> 필터는 {@code DispatcherServlet} 밖이라 그 예외는
 * {@code GlobalExceptionHandler}를 타지 않고, 그대로 두면 공통 오류 계약을 벗어난 응답이 나간다.
 *
 * <p><b>Spring Context를 올리지 않는다.</b> 이 필터는 빈이 아니라 {@code SecurityConfig}가 직접
 * 만들어 체인에 무는 것이라 손으로 조립할 수 있고, 대역을 빈으로 갈아끼우면 컨텍스트가 하나 더 떠
 * 공유 PostgreSQL 커넥션을 잡아먹는다(S15P11A705-267에서 실측).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("인증 필터의 인프라 실패 처리")
class JwtAuthenticationFilterDatabaseFailureTest {

	private static final long MEMBER_ID = 7L;
	private static final String ACCESS_TOKEN = "access-token-value";

	@Mock
	private JwtTokenProvider tokenProvider;

	@Mock
	private MemberRepository memberRepository;

	private JwtAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		filter = new JwtAuthenticationFilter(
			tokenProvider, memberRepository, new SecurityErrorWriter(JsonMapper.builder().build()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("탈퇴 판정이 DB 오류로 실패하면 503 공통 envelope을 내보낸다")
	void databaseFailureIsReportedAsServiceUnavailable() throws Exception {
		// 401로 삼키면 클라이언트가 재발급을 시도하는데, 재발급은 Redis만 쓰므로 성공한다. 그러면
		// 401 → 재발급 → 401이 DB가 회복될 때까지 돌고, 그 사이 동시 요청이 겹치면 재사용 감지로
		// 전 세션이 폐기된다(S15P11A705-267). 인프라 실패를 인증 실패로 말하지 않는 이유다.
		when(tokenProvider.parseAccessToken(anyString())).thenReturn(Optional.of(MEMBER_ID));
		when(memberRepository.isActive(MEMBER_ID))
			.thenThrow(new DataAccessResourceFailureException("DB 순단을 가장한다"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(requestWithAccessCookie(), response, chain);

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString())
			.contains("\"success\":false")
			.contains(ErrorCode.AUTH_UNAVAILABLE.getCode());
		assertThat(chain.getRequest())
			.as("인증을 확인하지 못한 요청을 컨트롤러까지 보내면 안 된다")
			.isNull();
	}

	@Test
	@DisplayName("탈퇴한 회원은 401 경로 그대로 — 필터가 응답을 쓰지 않는다")
	void withdrawnMemberStillFallsThroughToTheEntryPoint() throws Exception {
		// 인프라 실패와 탈퇴는 구별해야 한다. 후자는 자격증명 문제이므로 이 필터가 응답을 쓰지 않고
		// 컨텍스트를 비운 채 통과시켜 RestAuthenticationEntryPoint가 401 envelope을 만든다.
		when(tokenProvider.parseAccessToken(anyString())).thenReturn(Optional.of(MEMBER_ID));
		when(memberRepository.isActive(MEMBER_ID)).thenReturn(false);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(requestWithAccessCookie(), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).isEmpty();
		assertThat(chain.getRequest()).as("체인은 계속되어야 한다").isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("활성 회원은 그대로 인증된다")
	void activeMemberIsAuthenticated() throws Exception {
		when(tokenProvider.parseAccessToken(anyString())).thenReturn(Optional.of(MEMBER_ID));
		when(memberRepository.isActive(MEMBER_ID)).thenReturn(true);
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(requestWithAccessCookie(), new MockHttpServletResponse(), chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication())
			.isNotNull()
			.extracting(authentication -> ((MemberPrincipal)authentication.getPrincipal()).memberId())
			.isEqualTo(MEMBER_ID);
	}

	private MockHttpServletRequest requestWithAccessCookie() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie(AuthCookies.ACCESS_TOKEN, ACCESS_TOKEN));
		return request;
	}
}
