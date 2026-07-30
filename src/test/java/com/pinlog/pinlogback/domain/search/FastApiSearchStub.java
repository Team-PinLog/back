package com.pinlog.pinlogback.domain.search;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /internal/v1/search}를 대신 받는 FastAPI 대역.
 *
 * <p><b>이 대역은 일부러 거짓말을 할 수 있다.</b> 남의 Context·이미 지운 Context·존재하지 않는
 * Record를 결과로 돌려줄 수 있어야 "Spring이 FastAPI 응답을 믿지 않는다"(AI 설계 9.5)를 증명할 수
 * 있다. 진짜에 가까운 대역은 그 증명을 할 수 없다 — 진짜는 틀린 답을 주지 않기 때문이다.
 *
 * <p>{@link com.pinlog.pinlogback.domain.ai.FastApiProcessStub}과 같은 이유로 JDK
 * {@link HttpServer}를 쓴다. 다만 이 대역은 DB를 들여다보지 않는다 — 검색은 커밋 순서가 아니라
 * <b>응답을 어떻게 걸러내는가</b>가 검증 대상이라 응답을 프로그래밍할 수 있으면 충분하다.
 */
final class FastApiSearchStub {

	static final String PATH = "/internal/v1/search";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** FastAPI가 Record 단위로 집계해 돌려주는 한 건(AI 설계 9.4). 본문은 돌려주지 않는다. */
	record Match(long recordId, long contextId, double similarity) {
	}

	/**
	 * 요청 도착 시점의 관측 결과.
	 *
	 * @param userId 요청 본문의 {@code userId}. Spring이 인증에서 해석한 memberId여야 한다
	 * @param query 요청 본문의 {@code query}
	 * @param limit 요청 본문의 {@code limit}
	 * @param embeddingProfile 요청 본문의 {@code embeddingProfile}. 이 값이 대조의 입력이다
	 * @param internalSecret {@code X-Internal-Secret} 헤더 값
	 */
	record Received(long userId, String query, int limit, String embeddingProfile, String internalSecret) {
	}

	/** 대역의 응답 방식. */
	enum Mode {
		/** 200 + 프로그래밍된 결과. */
		RESULTS,
		/** 200이지만 {@code results} 필드가 아예 없다. 계약 위반이지만 500으로 번지면 안 된다. */
		MISSING_RESULTS_FIELD,
		/** 200 + 필수 필드가 {@code null}인 항목들. 역시 계약 위반이고 역시 500이면 안 된다. */
		MALFORMED_RESULTS,
		/**
		 * 200 + <b>배열 원소 자체가 {@code null}</b>. Pydantic이 {@code list[SearchResultItem]}에
		 * null을 허용하지 않으니 진짜 FastAPI는 이 형태를 만들지 못한다. 그래도 재현하는 이유는
		 * 최상위 {@code results}를 못 믿는 것과 원소를 믿는 것이 <b>층이 어긋나기</b> 때문이다 —
		 * 둘 다 계약상 올 수 없는 형태인데 한쪽만 방어하면 방어 범위가 javadoc의 약속과 달라진다.
		 */
		NULL_MATCH_ELEMENT,
		/** 422 + 양쪽 Profile 값(ai 레포 {@code main.py}의 {@code ProfileMismatchError} 핸들러). */
		PROFILE_MISMATCH,
		/** 422 + FastAPI 기본 요청 검증 오류 본문. Profile 불일치와 <b>같은 상태 코드</b>다. */
		VALIDATION_ERROR,
		/** 422지만 본문이 JSON이 아니다. 판정은 "불일치가 아니다"로 떨어져야 한다. */
		UNPARSEABLE_422,
		/** 401 — 시크릿·헤더 설정 문제. */
		UNAUTHORIZED,
		/** 5xx — 상대 장애. 본문이 비어 있는 경우를 함께 재현한다. */
		SERVER_ERROR,
		/** 응답 없이 연결을 끊는다 — 연결·타임아웃 계열 실패. */
		HANG_UP
	}

	private final HttpServer server;
	private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.RESULTS);
	private final AtomicReference<List<Match>> results = new AtomicReference<>(List.of());
	private final AtomicReference<Received> received = new AtomicReference<>();

	FastApiSearchStub() {
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException("FastAPI 검색 대역을 띄우지 못했다", e);
		}
		server.createContext(PATH, this::handle);
		server.setExecutor(Executors.newFixedThreadPool(2));
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** 정상 응답으로 되돌리고 결과를 갈아 끼운다. 순서가 곧 유사도 내림차순이다. */
	void willReturn(Match... matches) {
		mode.set(Mode.RESULTS);
		results.set(List.of(matches));
		received.set(null);
	}

	/** 실패, 또는 계약을 어긴 200. 어느 쪽이든 결과 목록은 쓰지 않는다. */
	void willRespondWith(Mode next) {
		mode.set(next);
		results.set(List.of());
		received.set(null);
	}

	/** 마지막으로 도착한 요청. 호출이 없었으면 {@code null}. 검색은 동기 호출이라 대기가 필요 없다. */
	Received lastCall() {
		return received.get();
	}

	void stop() {
		server.stop(0);
		if (server.getExecutor() instanceof ExecutorService executor) {
			executor.shutdownNow();
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
		received.set(new Received(
			body.get("userId").asLong(),
			body.get("query").asString(),
			body.get("limit").asInt(),
			body.get("embeddingProfile").asString(),
			exchange.getRequestHeaders().getFirst("X-Internal-Secret")));

		switch (mode.get()) {
			case HANG_UP -> exchange.close();
			case PROFILE_MISMATCH -> respond(exchange, 422, """
				{"detail":"embeddingProfile mismatch",\
				"requestProfile":"%s","serverProfile":"other-profile-v9"}"""
				.formatted(received.get().embeddingProfile()));
			case VALIDATION_ERROR -> respond(exchange, 422,
				"""
					{"detail":[{"type":"string_too_short","loc":["body","query"],"msg":"too short"}]}""");
			case UNPARSEABLE_422 -> respond(exchange, 422, "<html>gateway said no</html>");
			case UNAUTHORIZED -> respond(exchange, 401, "{\"detail\":\"invalid secret\"}");
			case SERVER_ERROR -> respond(exchange, 503, "");
			case MISSING_RESULTS_FIELD -> respond(exchange, 200, "{}");
			case MALFORMED_RESULTS -> respond(exchange, 200, """
				{"results":[\
				{"recordId":null,"contextId":900000001,"similarity":0.9},\
				{"recordId":900000002,"contextId":null,"similarity":0.8},\
				{"recordId":900000003,"contextId":900000003,"similarity":null}]}""");
			case NULL_MATCH_ELEMENT -> respond(exchange, 200, "{\"results\":[null]}");
			case RESULTS -> respond(exchange, 200, resultsJson());
		}
	}

	private String resultsJson() {
		String items = results.get().stream()
			.map(match -> "{\"recordId\":%d,\"contextId\":%d,\"similarity\":%s}"
				.formatted(match.recordId(), match.contextId(), match.similarity()))
			.collect(Collectors.joining(","));
		return "{\"results\":[" + items + "]}";
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
