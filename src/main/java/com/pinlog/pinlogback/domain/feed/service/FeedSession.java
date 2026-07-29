package com.pinlog.pinlogback.domain.feed.service;

import java.util.UUID;

import com.pinlog.pinlogback.global.exception.InvalidCursorException;
import com.pinlog.pinlogback.global.response.Cursor;

/**
 * Feed Session — {@code requestId}와 그 Session 안에서의 위치.
 *
 * <p>커서는 공통 규약({@code Base64(정렬키,id)})을 그대로 쓴다. 정렬키 자리에 {@code requestId},
 * id 자리에 offset을 넣으므로 Feed 전용 커서 형식을 새로 만들지 않는다. 위조·손상된 커서는
 * {@link Cursor#decode}가 {@link InvalidCursorException}(400)으로 처리한다 — 500이 아니다
 * (feed-tests Q6).
 *
 * <p><b>{@code requestId}가 후보 표본의 seed이기도 하다.</b> 그래서 다음 페이지가 같은 후보 풀과
 * 같은 정렬을 복원한다 — 페이지마다 후보를 새로 뽑으면 그 사이 정렬이 흔들려 중복·누락이 생긴다.
 *
 * @param requestId Feed Session 식별자
 * @param offset 이 Session의 정렬 결과에서 이번 페이지가 시작할 위치
 */
public record FeedSession(UUID requestId, int offset) {

	public static FeedSession start() {
		return new FeedSession(UUID.randomUUID(), 0);
	}

	/**
	 * 커서가 없거나 비어 있으면 새 Session을 시작한다. Session 식별자 형식이 깨진 커서는
	 * 위조로 보고 거절한다.
	 */
	public static FeedSession resolve(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return start();
		}
		Cursor decoded = Cursor.decode(cursor);
		if (decoded.id() < 0) {
			throw new InvalidCursorException();
		}
		return new FeedSession(parseRequestId(decoded.sortKey()), (int)Math.min(decoded.id(), Integer.MAX_VALUE));
	}

	public String encodeCursorAt(int nextOffset) {
		return Cursor.encode(requestId.toString(), nextOffset);
	}

	/**
	 * 무작위 채널의 표본 시작점을 정하는 seed. {@code requestId}에서만 나오므로 같은 Session의
	 * 모든 페이지가 같은 표본을 본다.
	 */
	public long seed() {
		return requestId.getMostSignificantBits() ^ requestId.getLeastSignificantBits();
	}

	private static UUID parseRequestId(String sortKey) {
		try {
			return UUID.fromString(sortKey);
		} catch (IllegalArgumentException e) {
			throw new InvalidCursorException();
		}
	}
}
