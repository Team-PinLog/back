package com.pinlog.pinlogback.global.response;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.pinlog.pinlogback.global.exception.InvalidCursorException;

/**
 * 목록 커서 페이지네이션에서 쓰는 불투명 커서. {@code Base64(정렬키,id)} 형태로 인코딩한다(공개 API 명세 1.4).
 *
 * <p>정렬키에 쉼표가 포함될 수 있으므로 디코딩 시 마지막 쉼표를 구분자로 취급한다.
 */
public record Cursor(String sortKey, long id) {

	private static final String SEPARATOR = ",";

	public static String encode(String sortKey, long id) {
		String raw = sortKey + SEPARATOR + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static String encode(Instant sortKey, long id) {
		return encode(sortKey.toString(), id);
	}

	public String encode() {
		return encode(sortKey, id);
	}

	/**
	 * 정렬키를 {@link Cursor#encode(Instant, long)}로 인코딩했을 때의 짝이 되는 접근자다.
	 * {@code sortKey()}를 호출부에서 직접 {@link Instant#parse(CharSequence)}하지 않고 이 메서드로만
	 * 얻어야 한다 — 그래야 커서 위조로 인한 파싱 실패가 {@link InvalidCursorException}(400)으로 처리된다.
	 */
	public Instant sortKeyAsInstant() {
		try {
			return Instant.parse(sortKey);
		} catch (DateTimeParseException e) {
			throw new InvalidCursorException();
		}
	}

	public static Cursor decode(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidCursorException();
		}

		String decoded = decodeBase64(raw);

		int separatorIndex = decoded.lastIndexOf(SEPARATOR);
		if (separatorIndex < 0) {
			throw new InvalidCursorException();
		}

		String sortKey = decoded.substring(0, separatorIndex);
		String idPart = decoded.substring(separatorIndex + 1);
		long id = parseId(idPart);

		return new Cursor(sortKey, id);
	}

	private static String decodeBase64(String raw) {
		try {
			return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			throw new InvalidCursorException();
		}
	}

	private static long parseId(String idPart) {
		try {
			return Long.parseLong(idPart);
		} catch (NumberFormatException e) {
			throw new InvalidCursorException();
		}
	}
}
