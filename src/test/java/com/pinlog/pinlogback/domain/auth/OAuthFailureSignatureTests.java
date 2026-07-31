package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 간헐적 {@code OAUTH_FAILED}의 하위 원인별 <b>로그 서명</b>을 실측한다(S15P11A705-187).
 *
 * <p>운영 로그로 원인 <b>B</b>({@code authorization_request_not_found})까지는 특정됐다. 남은 것은
 * 그 안의 하위 원인이고, 티켓의 완료 조건이 <b>중복 콜백이면 멱등 수렴</b>을 요구하므로 어느
 * 하위 원인인지가 수정 방향을 바꾼다.
 *
 * <p><b>이 클래스는 판별표다.</b> 각 시나리오가 남기는 (성공 라인 수, 실패 오류 코드) 조합을
 * 고정해, 운영 로그를 읽을 때 조회 한 번으로 하위 원인을 지목할 수 있게 한다. 서명이 겹치는
 * 시나리오가 있다면 그 사실 자체가 결과다 — 로그만으로는 갈리지 않는다는 뜻이고, 그때는 판별
 * 정보를 로그에 더해야 한다.
 *
 * <p>인가 요청 쿠키는 단일 슬롯이고({@code oauth2_auth_request}) 콜백에서 만료되며 수명이
 * 600초다. 그래서 하위 원인이 넷 나온다 — 중복 콜백, 로그인을 두 번 시작한 뒤 <b>나중</b> 것을
 * 먼저 완료, 같은 상황에서 <b>먼저</b> 것을 완료, 쿠키 유실·만료.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("OAUTH_FAILED 하위 원인별 로그 서명")
class OAuthFailureSignatureTests extends SocialLoginTestSupport {

	private static final String SUCCESS_KEYWORD = "social login succeeded";
	private static final String FAILURE_KEYWORD = "social login failed";

	private ListAppender<ILoggingEvent> appender;
	private List<Logger> attached;

	@BeforeEach
	void captureAuthLogs() {
		appender = new ListAppender<>();
		appender.start();
		attached = List.of(
			logger("com.pinlog.pinlogback.global.security.oauth"),
			logger("com.pinlog.pinlogback.domain.auth.service"));
		attached.forEach(logger -> {
			logger.setLevel(Level.INFO);
			logger.addAppender(appender);
		});
	}

	@AfterEach
	void releaseAuthLogs() {
		attached.forEach(logger -> logger.detachAppender(appender));
		appender.stop();
	}

	/**
	 * 하위 원인 ①. 같은 {@code state}로 콜백이 두 번 배달된다 — 더블클릭·프리페치·재시도가 만든다.
	 * 첫 콜백이 쿠키를 소비하므로 두 번째는 인가 요청을 찾지 못한다.
	 */
	@Test
	@DisplayName("① 중복 콜백 — 성공 1줄 + authorization_request_not_found")
	void duplicateCallback() throws Exception {
		provider.useSubject("sig-duplicate");
		HttpClient client = newClient();
		String state = startLoginAndCaptureState(client, port);

		callback(client, port, state);
		callback(client, port, state);

		assertThat(successLines()).hasSize(1);
		assertThat(failureLine()).contains("authorization_request_not_found");
	}

	/**
	 * 하위 원인 ②. 로그인을 두 번 시작하면 단일 슬롯이라 <b>나중</b> 인가 요청이 쿠키를 덮는다.
	 * 그 나중 흐름을 먼저 완료하면 쿠키가 소비되고, 뒤늦게 도착한 <b>먼저</b> 흐름의 콜백은 찾을
	 * 것이 없다.
	 *
	 * <p>①과 서명이 같은지가 이 티켓의 핵심 질문이다.
	 */
	@Test
	@DisplayName("② 로그인 두 번 시작 → 나중 것을 먼저 완료")
	void twoStartsLaterCompletedFirst() throws Exception {
		provider.useSubject("sig-later-first");
		HttpClient client = newClient();
		String first = startLoginAndCaptureState(client, port);
		String second = startLoginAndCaptureState(client, port);

		callback(client, port, second);
		callback(client, port, first);

		assertThat(successLines()).hasSize(1);
		assertThat(failureLine()).contains("authorization_request_not_found");
	}

	/**
	 * 하위 원인 ③. 같은 상황에서 <b>먼저</b> 흐름을 완료한다. 쿠키에는 나중 {@code state}가 들어
	 * 있어 인가 요청은 <b>찾아지지만</b> state가 어긋난다 — 다른 오류 코드로 갈릴 것으로 본다.
	 */
	@Test
	@DisplayName("③ 로그인 두 번 시작 → 먼저 것을 완료 (state 불일치)")
	void twoStartsEarlierCompleted() throws Exception {
		provider.useSubject("sig-earlier");
		HttpClient client = newClient();
		String first = startLoginAndCaptureState(client, port);
		startLoginAndCaptureState(client, port);

		callback(client, port, first);

		assertThat(successLines()).isEmpty();
		assertThat(failureLine()).contains("invalid_state_parameter");
	}

	/**
	 * 하위 원인 ④. 쿠키가 사라진다 — 600초 초과, 브라우저 차단, 시크릿창 종료가 만든다. 만료를
	 * 기다리는 대신 쿠키 저장소를 비워 같은 상태를 만든다.
	 *
	 * <p>성공 라인이 <b>없다</b>는 점이 ①·②와 갈리는 지점이다.
	 */
	@Test
	@DisplayName("④ 인가 요청 쿠키 유실 — 성공 라인 없음")
	void authorizationCookieLost() throws Exception {
		provider.useSubject("sig-cookie-lost");
		CookieManager cookies = new CookieManager();
		HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.cookieHandler(cookies)
			.build();
		String state = startLoginAndCaptureState(client, port);
		cookies.getCookieStore().removeAll();

		callback(client, port, state);

		assertThat(successLines()).isEmpty();
		assertThat(failureLine()).contains("authorization_request_not_found");
	}

	private Logger logger(String name) {
		return (Logger)LoggerFactory.getLogger(name);
	}

	private List<String> allLines() {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private List<String> successLines() {
		return allLines().stream().filter(line -> line.contains(SUCCESS_KEYWORD)).toList();
	}

	/** 실패 라인은 시나리오마다 정확히 하나여야 한다 — 여러 줄이면 서명이 성립하지 않는다. */
	private String failureLine() {
		List<String> failures = allLines().stream().filter(line -> line.contains(FAILURE_KEYWORD)).toList();
		assertThat(failures).as("실패 라인").hasSize(1);
		return failures.getFirst();
	}
}
