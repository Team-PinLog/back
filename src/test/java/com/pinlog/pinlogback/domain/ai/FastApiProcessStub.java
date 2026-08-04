package com.pinlog.pinlogback.domain.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /internal/v1/context/process}를 대신 받는 FastAPI 대역.
 *
 * <p><b>요청을 받은 그 시점에 DB를 직접 들여다보는 것이 이 대역의 존재 이유다.</b> 검증하려는 것은
 * "언젠가 PENDING이 커밋됐다"가 아니라 <b>"호출이 도착한 시점에 이미 커밋돼 있었다"</b>이고, 그
 * 시점은 요청 핸들러 안에서만 포착된다. 테스트가 끝난 뒤 조회하면 앞의 명제만 증명되어 순서를
 * 뒤집어도 통과한다.
 *
 * <p>조회는 애플리케이션 커넥션 풀이 아니라 {@link DriverManager}로 새로 연 커넥션으로 한다. 같은
 * 커넥션을 재사용하면 미커밋 변경도 보이므로 증명이 성립하지 않는다. 별 프로세스인 AI 워커가
 * 보는 것과 같은 조건을 만든다.
 *
 * <p>HTTP 대역에 라이브러리를 붙이지 않고 JDK {@link HttpServer}를 쓴 이유: 핸들러 안에서 임의의
 * 코드(별 커넥션 DB 조회)를 돌려야 하는데, 요청·응답 기록만 해 주는 mock 서버로는 그 지점을 잡을
 * 수 없다. 새 테스트 의존성도 필요 없다.
 */
public final class FastApiProcessStub {

	public static final String PATH = "/internal/v1/context/process";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * 요청 도착 시점의 관측 결과.
	 *
	 * @param contextId 요청 본문의 {@code contextId}
	 * @param internalSecret {@code X-Internal-Secret} 헤더 값
	 * @param text 요청 본문의 {@code text}
	 * @param stateVisible 그 시점에 별 커넥션에서 {@code ai.context_ai_state} 행이 보였는가
	 * @param embeddingStatus 보였다면 그 값. 아니면 {@code null}
	 * @param keywordStatus 보였다면 그 값. 아니면 {@code null}
	 */
	public record Received(
		long contextId,
		String internalSecret,
		String text,
		boolean stateVisible,
		String embeddingStatus,
		String keywordStatus
	) {
	}

	/** 대역의 응답 방식. 실패 경로가 롤백을 유발하지 않는 것을 확인하려면 실패를 만들 수 있어야 한다. */
	public enum Mode {
		/** 정상 접수. */
		ACCEPTED,
		/** 5xx — 상대 장애. */
		SERVER_ERROR,
		/** 응답 없이 연결을 끊는다 — 연결·타임아웃 계열 실패에 해당한다. */
		HANG_UP
	}

	private final HttpServer server;
	private final PostgreSQLContainer postgres;
	private final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
	private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ACCEPTED);

	public FastApiProcessStub(PostgreSQLContainer postgres) {
		this.postgres = postgres;
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException("FastAPI 대역을 띄우지 못했다", e);
		}
		server.createContext(PATH, this::handle);
		server.setExecutor(Executors.newFixedThreadPool(2));
		server.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public void reset(Mode next) {
		mode.set(next);
		received.clear();
	}

	/** 호출이 오지 않으면 {@code null}. 비동기 호출이라 폴링이 아니라 대기로 받는다. */
	public Received awaitCall() throws InterruptedException {
		return received.poll(15, TimeUnit.SECONDS);
	}

	/** 호출이 오지 <b>않았음</b>을 확인할 때 쓴다. 짧게 기다린 뒤 비어 있으면 참으로 본다. */
	public boolean noCallWithin(long millis) throws InterruptedException {
		return received.poll(millis, TimeUnit.MILLISECONDS) == null;
	}

	/**
	 * 대역을 쓰는 클래스가 끝날 때 닫는다. {@code stop}은 실행자를 건드리지 않으므로 직접 내린다 —
	 * 그러지 않으면 non-daemon 스레드 둘이 JVM 끝까지 남는다.
	 */
	public void stop() {
		server.stop(0);
		if (server.getExecutor() instanceof ExecutorService executor) {
			executor.shutdownNow();
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
		long contextId = body.get("contextId").asLong();
		received.add(observe(exchange, body, contextId));

		Mode current = mode.get();
		if (current == Mode.HANG_UP) {
			exchange.close();
			return;
		}
		exchange.sendResponseHeaders(current == Mode.ACCEPTED ? 202 : 503, -1);
		exchange.close();
	}

	private Received observe(HttpExchange exchange, JsonNode body, long contextId) {
		String secret = exchange.getRequestHeaders().getFirst("X-Internal-Secret");
		String text = body.get("text").asString();
		try (Connection connection = DriverManager.getConnection(
			postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			PreparedStatement statement = connection.prepareStatement(
				"SELECT embedding_status, keyword_status FROM ai.context_ai_state WHERE context_id = ?")) {
			statement.setLong(1, contextId);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					return new Received(contextId, secret, text, false, null, null);
				}
				return new Received(contextId, secret, text, true, rows.getString(1), rows.getString(2));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("대역에서 ai.context_ai_state를 조회하지 못했다", e);
		}
	}
}
