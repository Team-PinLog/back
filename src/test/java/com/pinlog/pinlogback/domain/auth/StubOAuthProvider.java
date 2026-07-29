package com.pinlog.pinlogback.domain.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 소셜 공급자 대역. token·userinfo 두 엔드포인트만 흉내 낸다.
 *
 * <p>새 의존성을 들이지 않으려고 JDK 내장 {@link HttpServer}를 쓴다.
 *
 * <p><b>OIDC는 흉내 내지 않는다.</b> openid scope를 주면 Spring이 서명된 id_token과 JWKS 검증을
 * 요구하는데, 그건 프레임워크 책임이라 우리 코드를 검증하는 데 보탬이 없다. 대신 테스트 등록정보에서
 * scope를 email로 두어 일반 OAuth2 경로를 태운다. 우리 코드(정규화·회원 생성·쿠키 발급)는
 * 성공 핸들러에 있어 OIDC 경로에서도 같은 코드가 돈다.
 */
final class StubOAuthProvider {

	static final String EMAIL = "tester@example.com";

	private final HttpServer server;
	private final AtomicInteger tokenRequests = new AtomicInteger();

	/**
	 * userinfo가 돌려줄 식별자. 테스트끼리 DB를 공유하므로(롤백 없음) 테스트마다 다른 값을 준다.
	 * 같은 값을 쓰면 먼저 돈 테스트가 만든 회원을 뒤 테스트가 재사용해 버린다.
	 */
	private volatile String subject = "google-sub-default";

	/** userinfo가 돌려줄 이메일. 저장 실패 경로를 태우려면 컬럼 상한을 넘기는 값이 필요하다. */
	private volatile String email = EMAIL;

	private StubOAuthProvider(HttpServer server) {
		this.server = server;
	}

	static StubOAuthProvider start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		StubOAuthProvider stub = new StubOAuthProvider(server);

		server.createContext("/token", exchange -> {
			stub.tokenRequests.incrementAndGet();
			respond(exchange, """
				{"access_token":"stub-access-token","token_type":"Bearer","expires_in":3600}""");
		});
		server.createContext("/userinfo", exchange -> respond(exchange, """
			{"sub":"%s","email":"%s"}""".formatted(stub.subject, stub.email)));

		server.start();
		return stub;
	}

	/** @return 설정한 식별자. 테스트에서 그대로 검증에 쓴다. */
	String useSubject(String value) {
		this.subject = value;
		return value;
	}

	/** 저장 실패 경로 검증용. 다음 테스트에 새지 않도록 호출한 테스트가 되돌린다. */
	void useEmail(String value) {
		this.email = value;
	}

	void stop() {
		server.stop(0);
	}

	String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	int tokenRequestCount() {
		return tokenRequests.get();
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
		exchange.sendResponseHeaders(200, payload.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(payload);
		}
	}
}
