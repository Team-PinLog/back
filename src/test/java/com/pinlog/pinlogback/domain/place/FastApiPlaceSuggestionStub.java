package com.pinlog.pinlogback.domain.place;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class FastApiPlaceSuggestionStub {

	static final String PATH = "/internal/v1/place-suggestions";

	enum Mode {
		SUCCESS,
		BAD_REQUEST,
		UNAUTHORIZED,
		TOO_LARGE,
		UNSUPPORTED_MEDIA_TYPE,
		INTERNAL_ERROR,
		UPSTREAM_ERROR,
		UNAVAILABLE,
		BUSY,
		TIMEOUT,
		BLOCK
	}

	record Received(String internalSecret, String traceId, String contentType, byte[] body) {
	}

	private static final String SUCCESS_BODY = "{"
		+ "\"requestId\":\"req_test\","
		+ "\"candidates\":[{"
		+ "\"candidateId\":\"candidate-1\","
		+ "\"extracted\":{"
		+ "\"placeName\":\"Zootopia Seoul\","
		+ "\"regionHints\":[\"Seoul\"],"
		+ "\"branchHint\":null,"
		+ "\"evidence\":[\"Zootopia Seoul\"],"
		+ "\"contextSuggestion\":\"A pizza place only in Daegu and Seoul\""
		+ "},"
		+ "\"kakaoSearch\":{"
		+ "\"status\":\"SUCCESS\","
		+ "\"query\":\"Zootopia Seoul\","
		+ "\"items\":[{"
		+ "\"kakaoPlaceId\":\"12345\","
		+ "\"name\":\"Zootopia Seoul\","
		+ "\"categoryName\":\"Restaurant\","
		+ "\"address\":\"Seoul\","
		+ "\"roadAddress\":null,"
		+ "\"phone\":null,"
		+ "\"placeUrl\":\"http://place.map.kakao.com/12345\","
		+ "\"lat\":37.5,"
		+ "\"lng\":127.0"
		+ "}]"
		+ "}"
		+ "}],"
		+ "\"warnings\":[]"
		+ "}";

	private final HttpServer server;
	private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);
	private final AtomicReference<Received> received = new AtomicReference<>();
	private final AtomicReference<CountDownLatch> requestStarted = new AtomicReference<>(new CountDownLatch(1));
	private final AtomicReference<CountDownLatch> releaseRequest = new AtomicReference<>(new CountDownLatch(1));

	FastApiPlaceSuggestionStub() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new IllegalStateException("FastAPI place suggestion stub failed to start", e);
		}
		server.createContext(PATH, this::handle);
		server.setExecutor(Executors.newFixedThreadPool(2));
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	void willRespondWith(Mode next) {
		mode.set(next);
		received.set(null);
		requestStarted.set(new CountDownLatch(1));
		releaseRequest.set(new CountDownLatch(1));
	}

	Received lastCall() {
		return received.get();
	}

	boolean awaitRequest() throws InterruptedException {
		return requestStarted.get().await(2, TimeUnit.SECONDS);
	}

	void releaseBlockedRequest() {
		releaseRequest.get().countDown();
	}

	void stop() {
		server.stop(0);
		if (server.getExecutor() instanceof ExecutorService executor) {
			executor.shutdownNow();
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		received.set(new Received(
			exchange.getRequestHeaders().getFirst("X-Internal-Secret"),
			exchange.getRequestHeaders().getFirst("X-Trace-Id"),
			exchange.getRequestHeaders().getFirst("Content-Type"),
			exchange.getRequestBody().readAllBytes()));
		switch (mode.get()) {
			case SUCCESS -> respond(exchange, 200, SUCCESS_BODY);
			case BAD_REQUEST -> respond(exchange, 400, "{\"detail\":\"invalid image\"}");
			case UNAUTHORIZED -> respond(exchange, 401, "{\"detail\":\"invalid internal secret\"}");
			case TOO_LARGE -> respond(exchange, 413, "{\"detail\":\"image too large\"}");
			case UNSUPPORTED_MEDIA_TYPE -> respond(exchange, 415, "{\"detail\":\"unsupported image type\"}");
			case INTERNAL_ERROR -> respond(exchange, 500, "");
			case UPSTREAM_ERROR -> respond(exchange, 502, "");
			case UNAVAILABLE -> respond(exchange, 503, "");
			case BUSY -> respond(exchange, 503, "{\"detail\":\"PLACE_SUGGESTION_BUSY\"}");
			case TIMEOUT -> {
				sleep();
				exchange.close();
			}
			case BLOCK -> {
				requestStarted.get().countDown();
				awaitRelease();
				respond(exchange, 200, SUCCESS_BODY);
			}
		}
	}

	private void sleep() {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private void awaitRelease() {
		try {
			releaseRequest.get().await(2, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
