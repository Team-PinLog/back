package com.pinlog.pinlogback.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class CursorPageTest {

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Test
	void ofWithNextCursorHasNext() {
		CursorPage<String> page = CursorPage.of(List.of("a", "b"), "cursor-1");

		assertThat(page.items()).containsExactly("a", "b");
		assertThat(page.nextCursor()).isEqualTo("cursor-1");
		assertThat(page.hasNext()).isTrue();
	}

	@Test
	void lastPageHasNoNextCursor() {
		CursorPage<String> page = CursorPage.last(List.of("a"));

		assertThat(page.nextCursor()).isNull();
		assertThat(page.hasNext()).isFalse();
	}

	@Test
	void emptyPageHasNoItemsAndNoNext() {
		CursorPage<String> page = CursorPage.empty();

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.hasNext()).isFalse();
	}

	@Test
	void normalizeSizeUsesDefaultWhenAbsent() {
		assertThat(CursorPage.normalizeSize(null)).isEqualTo(CursorPage.DEFAULT_SIZE);
	}

	@Test
	void normalizeSizeCapsAtMax() {
		assertThat(CursorPage.normalizeSize(CursorPage.MAX_SIZE + 1)).isEqualTo(CursorPage.MAX_SIZE);
	}

	@Test
	void normalizeSizeRejectsNonPositiveByFallingBackToDefault() {
		assertThat(CursorPage.normalizeSize(0)).isEqualTo(CursorPage.DEFAULT_SIZE);
		assertThat(CursorPage.normalizeSize(-5)).isEqualTo(CursorPage.DEFAULT_SIZE);
	}

	@Test
	void serializesWithItemsNextCursorHasNext() {
		String json = objectMapper.writeValueAsString(CursorPage.of(List.of("a"), "cursor-1"));

		assertThat(json).contains("\"items\":[\"a\"]")
			.contains("\"nextCursor\":\"cursor-1\"")
			.contains("\"hasNext\":true");
	}

	@Test
	void lastPageSerializesNextCursorAsNull() {
		String json = objectMapper.writeValueAsString(CursorPage.last(List.of("a")));

		assertThat(json).contains("\"nextCursor\":null");
	}
}
