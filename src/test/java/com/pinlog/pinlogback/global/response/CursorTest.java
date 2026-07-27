package com.pinlog.pinlogback.global.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.pinlog.pinlogback.global.exception.InvalidCursorException;

class CursorTest {

	@Test
	void encodesSortKeyAndIdAsBase64() {
		String encoded = Cursor.encode("2026-07-23T10:00:00Z", 8801L);

		String decoded = new String(Base64.getUrlDecoder().decode(encoded));
		assertThat(decoded).isEqualTo("2026-07-23T10:00:00Z,8801");
	}

	@Test
	void encodesInstantSortKeyAsIsoUtc() {
		String encoded = Cursor.encode(Instant.parse("2026-07-23T10:00:00Z"), 8801L);

		assertThat(Cursor.decode(encoded).sortKey()).isEqualTo("2026-07-23T10:00:00Z");
	}

	@Test
	void decodeRestoresSortKeyAndId() {
		Cursor cursor = Cursor.decode(Cursor.encode("2026-07-23T10:00:00Z", 8801L));

		assertThat(cursor.sortKey()).isEqualTo("2026-07-23T10:00:00Z");
		assertThat(cursor.id()).isEqualTo(8801L);
	}

	@Test
	void decodeRejectsNonBase64() {
		assertThatThrownBy(() -> Cursor.decode("not-base64!!"))
			.isInstanceOf(InvalidCursorException.class);
	}

	@Test
	void decodeRejectsMissingSeparator() {
		String malformed = Base64.getUrlEncoder().withoutPadding()
			.encodeToString("no-separator".getBytes());

		assertThatThrownBy(() -> Cursor.decode(malformed))
			.isInstanceOf(InvalidCursorException.class);
	}

	@Test
	void decodeRejectsNonNumericId() {
		String malformed = Base64.getUrlEncoder().withoutPadding()
			.encodeToString("2026-07-23T10:00:00Z,abc".getBytes());

		assertThatThrownBy(() -> Cursor.decode(malformed))
			.isInstanceOf(InvalidCursorException.class);
	}

	@Test
	void sortKeyMayContainNoCommaBeyondSeparator() {
		Cursor cursor = Cursor.decode(Cursor.encode("2026-07-23T10:00:00Z", 1L));

		assertThat(cursor.id()).isEqualTo(1L);
	}
}
