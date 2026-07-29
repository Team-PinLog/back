package com.pinlog.pinlogback.domain.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pinlog.pinlogback.global.exception.InvalidCursorException;

/**
 * Feed Session 커서 왕복(feed-tests 11장 Q4~Q6).
 */
class FeedSessionTests {

	@Test
	void missingCursorStartsNewSession() {
		assertThat(FeedSession.resolve(null).offset()).isZero();
		assertThat(FeedSession.resolve("  ").offset()).isZero();
		assertThat(FeedSession.resolve(null).requestId()).isNotNull();
	}

	@Test
	void cursorRoundTripPreservesSessionAndPosition() {
		FeedSession first = FeedSession.start();

		FeedSession resumed = FeedSession.resolve(first.encodeCursorAt(20));

		assertThat(resumed.requestId()).isEqualTo(first.requestId());
		assertThat(resumed.offset()).isEqualTo(20);
		assertThat(resumed.seed()).isEqualTo(first.seed());
	}

	/** Q5 — 커서에 내부 식별자·점수가 평문으로 드러나지 않는다. */
	@Test
	void cursorDoesNotExposeInternalsInPlainText() {
		FeedSession session = FeedSession.start();

		String cursor = session.encodeCursorAt(40);

		assertThat(cursor).matches("[A-Za-z0-9_-]+");
		assertThat(cursor).doesNotContain(session.requestId().toString());
	}

	/** Q6 — 위조·손상된 커서는 400이다. 500이 아니다. */
	@Test
	void tamperedCursorIsRejectedAsBadRequest() {
		String notBase64 = "!!!not-a-cursor!!!";
		String wrongShape = encode("nope");
		String notUuid = encode("not-a-uuid,3");
		String negativeOffset = encode(UUID.randomUUID() + ",-1");

		assertThatThrownBy(() -> FeedSession.resolve(notBase64)).isInstanceOf(InvalidCursorException.class);
		assertThatThrownBy(() -> FeedSession.resolve(wrongShape)).isInstanceOf(InvalidCursorException.class);
		assertThatThrownBy(() -> FeedSession.resolve(notUuid)).isInstanceOf(InvalidCursorException.class);
		assertThatThrownBy(() -> FeedSession.resolve(negativeOffset))
			.isInstanceOf(InvalidCursorException.class);
	}

	private String encode(String raw) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
