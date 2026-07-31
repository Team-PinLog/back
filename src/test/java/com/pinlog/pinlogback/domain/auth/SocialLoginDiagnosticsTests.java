package com.pinlog.pinlogback.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
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
 * 소셜 로그인 진단 로그(S15P11A705-186).
 *
 * <p><b>이 테스트가 존재하는 이유가 운영 조사에서 막힌 지점이다.</b> 간헐적
 * {@code OAUTH_FAILED}를 Loki로 조사해 분류까지는 갔지만(`[authorization_request_not_found]`)
 * 그 하위 원인 둘 — 중복 콜백인지 로그인을 두 번 시작한 것인지 — 을 가르지 못했다. 둘 다
 * <b>다른 요청이 성공했는가</b>를 봐야 갈리는데 성공 경로가 로그를 남기지 않았기 때문이다.
 *
 * <p>그래서 단언의 핵심은 "성공과 실패가 짝지어 보이는가"다. 로그 문구를 고정하는 것은 부수
 * 효과이고, 목적은 <b>운영에서 같은 조사를 다시 할 수 있는가</b>다.
 *
 * <p>로그 포착은 {@code TraceIdFilterTest}와 같은 방식({@code ListAppender})이다. 실제 서버
 * 스레드에서 찍히지만 같은 JVM이라 appender에 그대로 담긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("소셜 로그인 진단 로그")
class SocialLoginDiagnosticsTests extends SocialLoginTestSupport {

	private static final String SUCCESS_KEYWORD = "social login succeeded";
	private static final String SIGNUP_KEYWORD = "member signed up";
	private static final String FAILURE_KEYWORD = "social login failed";

	private ListAppender<ILoggingEvent> appender;
	private List<Logger> attached;

	@BeforeEach
	void captureAuthLogs() {
		appender = new ListAppender<>();
		appender.start();
		// 로그를 남기는 세 지점을 함께 본다 — 성공 핸들러, 실패 핸들러, 가입 서비스.
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

	@Test
	@DisplayName("로그인 성공이 로그에 남는다 — memberId와 provider를 포함한다")
	void successfulLoginIsLogged() throws Exception {
		String subject = provider.useSubject("diag-success-1");

		HttpResponse<String> callback = completeLogin(port, subject);

		assertThat(callback.statusCode()).isBetween(300, 399);
		assertThat(linesContaining(SUCCESS_KEYWORD))
			.as("성공 경로가 로그를 남겨야 실패와 짝지어 볼 수 있다")
			.hasSize(1)
			.allSatisfy(line -> assertThat(line).contains("memberId=").contains("GOOGLE"));
	}

	@Test
	@DisplayName("신규 가입은 로그인과 별개 사건으로 남는다")
	void signUpIsLoggedAsItsOwnEvent() throws Exception {
		String subject = provider.useSubject("diag-signup-1");

		completeLogin(port, subject);
		assertThat(linesContaining(SIGNUP_KEYWORD))
			.as("가입은 주요 상태 변화다")
			.hasSize(1);

		appender.list.clear();
		completeLogin(port, subject);

		assertThat(linesContaining(SIGNUP_KEYWORD))
			.as("기존 회원 로그인은 가입이 아니다")
			.isEmpty();
		assertThat(linesContaining(SUCCESS_KEYWORD)).hasSize(1);
	}

	/**
	 * 운영에서 관측된 실패를 그대로 재현한다. 같은 {@code state}로 콜백을 두 번 부르면 첫 번째가
	 * 인가 요청 쿠키를 소비하고 두 번째는 찾지 못한다 — 중복 콜백이 남기는 흔적과 같다.
	 *
	 * <p><b>이것이 이 티켓의 목적이다.</b> 조사에서 필요했던 것은 실패 옆에 성공이 있었는지였고,
	 * 이제 두 줄이 함께 남는다.
	 */
	@Test
	@DisplayName("중복 콜백은 성공 한 줄과 실패 한 줄을 함께 남긴다")
	void duplicateCallbackLeavesSuccessAndFailurePaired() throws Exception {
		provider.useSubject("diag-duplicate-1");
		HttpClient client = newClient();
		String state = startLoginAndCaptureState(client, port);

		HttpResponse<String> first = callback(client, port, state);
		HttpResponse<String> second = callback(client, port, state);

		assertThat(first.statusCode()).isBetween(300, 399);
		assertThat(second.statusCode()).isBetween(300, 399);
		assertThat(linesContaining(SUCCESS_KEYWORD)).hasSize(1);
		assertThat(linesContaining(FAILURE_KEYWORD))
			.hasSize(1)
			.allSatisfy(line -> assertThat(line).contains("authorization_request_not_found"));
	}

	@Test
	@DisplayName("실패 로그에 예외 타입이 남는다")
	void failureLogCarriesExceptionType() throws Exception {
		provider.useSubject("diag-type-1");
		HttpClient client = newClient();
		String state = startLoginAndCaptureState(client, port);
		callback(client, port, state);
		callback(client, port, state);

		// 메시지만 남기면 내부 실패(Redis·중복키)와 Spring이 던진 OAuth 오류가 뭉개진다.
		assertThat(linesContaining(FAILURE_KEYWORD))
			.hasSize(1)
			.allSatisfy(line -> assertThat(line).contains("OAuth2AuthenticationException"));
	}

	/**
	 * 내부 실패도 어느 단계에서 터졌는지 알 수 있어야 한다. <b>별도 단계 라벨을 두지 않고 예외
	 * 타입·메시지가 그 일을 한다</b> — 정규화 실패는 "필수 속성이 없다"를 든
	 * {@code IllegalStateException}으로 나오므로 두 정보가 함께 있으면 단계가 특정된다.
	 *
	 * <p>라벨을 뺀 이유는 정보가 늘지 않기 때문이다. 실패 핸들러가 타입을 함께 남기므로
	 * {@code stage=normalize}는 같은 것을 두 번 말한다.
	 */
	@Test
	@DisplayName("내부 실패는 예외 타입과 메시지로 단계가 특정된다")
	void internalFailureIsAttributableToItsStage() throws Exception {
		provider.useSubject("diag-stage-1");
		provider.useEmail(null);
		try {
			completeLogin(port, "diag-stage-1");
		} finally {
			provider.useEmail("user@example.com");
		}

		assertThat(linesContaining(FAILURE_KEYWORD))
			.hasSize(1)
			.allSatisfy(line -> assertThat(line)
				.contains("InternalAuthenticationServiceException")
				.contains("필수 속성이 없다"));
		assertThat(linesContaining(SUCCESS_KEYWORD))
			.as("정규화에서 끊겼으므로 세션이 발급되지 않는다")
			.isEmpty();
	}

	@Test
	@DisplayName("로그에 이메일·공급자 식별자가 나타나지 않는다")
	void logsDoNotContainPersonalData() throws Exception {
		String subject = provider.useSubject("diag-privacy-1");

		completeLogin(port, subject);

		// memberId는 내부 식별자라 무해하지만 이메일은 개인정보다(logging.md).
		assertThat(allLines())
			.isNotEmpty()
			.allSatisfy(line -> assertThat(line)
				.doesNotContain("@")
				.doesNotContain(subject));
	}

	private Logger logger(String name) {
		return (Logger)LoggerFactory.getLogger(name);
	}

	private List<String> allLines() {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private List<String> linesContaining(String keyword) {
		return allLines().stream().filter(line -> line.contains(keyword)).toList();
	}
}
