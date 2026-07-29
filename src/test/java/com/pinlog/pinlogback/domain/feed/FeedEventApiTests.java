package com.pinlog.pinlogback.domain.feed;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import com.pinlog.pinlogback.domain.feed.entity.FeedEventType;
import com.pinlog.pinlogback.domain.feed.repository.FeedEventRepository;
import com.pinlog.pinlogback.domain.feed.repository.FeedEventRepository.FeedEventRow;
import com.pinlog.pinlogback.global.common.InputLimits;

import tools.jackson.databind.JsonNode;

/**
 * 이벤트 수집과 노출 패널티(feed-tests 7장 E1·E4·E5·E8·E9).
 *
 * <p>E5와 E9를 함께 봐야 이 엔드포인트의 계약이 드러난다 — 개별 항목의 무효는 조용히 버리고
 * 204, 요청 전체의 크기 위반은 400이다. 한쪽만 검증하면 구현이 어느 쪽으로도 흘러간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedEventApiTests extends FeedFixtures {

	private static final String EVENTS_URL = "/v1/feed/events";

	@Autowired
	private FeedEventRepository eventRepository;

	/** E1 — Feed 응답으로 나간 항목이 position과 함께 IMPRESSION으로 기록된다. */
	@Test
	void respondingRecordsImpressionsWithPosition() throws Exception {
		long viewer = newMemberId();
		long collectionId = publishedCollection(newMemberId(), uniqueSeed("impression"));

		JsonNode response = feed(viewer, "?size=100");
		UUID requestId = UUID.fromString(response.at("/data/requestId").asString());

		Integer position = jdbcTemplate.queryForObject("""
			SELECT position FROM core.feed_event
			WHERE member_id = ? AND collection_id = ? AND request_id = ? AND event = 'IMPRESSION'
			""", Integer.class, viewer, collectionId, requestId);
		assertThat(position).isNotNull().isGreaterThanOrEqualTo(0);
		assertThat(collectionIdsOf(response)).contains(collectionId);
	}

	/** E4 — 저장되는 {@code member_id}는 본문 값이 아니라 인증 컨텍스트의 값이다. */
	@Test
	void memberIdComesFromAuthenticationNotTheBody() throws Exception {
		long reporter = newMemberId();
		long victim = newMemberId();
		long collectionId = publishedCollection(newMemberId(), uniqueSeed("event-owner"));
		UUID requestId = UUID.randomUUID();

		mockMvc.perform(post(EVENTS_URL).with(loginAs(reporter))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					\t"requestId": "%s",
					\t"memberId": %d,
					\t"events": [{ "event": "CLICK", "collectionId": %d, "position": 3 }]
					}
					""".formatted(requestId, victim, collectionId)))
			.andExpect(status().isNoContent());

		List<Long> owners = jdbcTemplate.queryForList(
			"SELECT member_id FROM core.feed_event WHERE request_id = ?", Long.class, requestId);
		assertThat(owners).containsExactly(reporter);
	}

	/** E5 — 실재하지 않는 {@code collectionId}는 그 건만 버리고 나머지는 저장한다. 204다. */
	@Test
	void unknownCollectionIsDroppedWithoutFailingTheRequest() throws Exception {
		long reporter = newMemberId();
		long collectionId = publishedCollection(newMemberId(), uniqueSeed("event-partial"));
		UUID requestId = UUID.randomUUID();

		mockMvc.perform(post(EVENTS_URL).with(loginAs(reporter))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					\t"requestId": "%s",
					\t"events": [
					\t\t{ "event": "CLICK", "collectionId": %d, "position": 0 },
					\t\t{ "event": "SAVE", "collectionId": 999999999, "placeId": 1 }
					\t]
					}
					""".formatted(requestId, collectionId)))
			.andExpect(status().isNoContent());

		List<Long> stored = jdbcTemplate.queryForList(
			"SELECT collection_id FROM core.feed_event WHERE request_id = ?", Long.class, requestId);
		assertThat(stored).containsExactly(collectionId);
	}

	/** E9 — 상한 초과는 초과분만 잘라내지 않고 요청 전체를 400으로 거절한다. */
	@Test
	void oversizedEventArrayIsRejected() throws Exception {
		long reporter = newMemberId();
		long collectionId = publishedCollection(newMemberId(), uniqueSeed("event-oversize"));
		UUID requestId = UUID.randomUUID();
		String events = IntStream.range(0, InputLimits.FEED_EVENTS_MAX + 1)
			.mapToObj(index -> "{ \"event\": \"CLICK\", \"collectionId\": " + collectionId + " }")
			.collect(Collectors.joining(", "));

		mockMvc.perform(post(EVENTS_URL).with(loginAs(reporter))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requestId\": \"" + requestId + "\", \"events\": [" + events + "]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

		assertThat(jdbcTemplate.queryForList(
			"SELECT id FROM core.feed_event WHERE request_id = ?", Long.class, requestId)).isEmpty();
	}

	/** E3 — IMPRESSION은 서버가 기록하는 값이므로 클라이언트가 보내면 400이다. */
	@Test
	void clientReportedImpressionIsRejectedAtTheEndpoint() throws Exception {
		long reporter = newMemberId();
		long collectionId = publishedCollection(newMemberId(), uniqueSeed("event-impression"));

		mockMvc.perform(post(EVENTS_URL).with(loginAs(reporter))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					\t"requestId": "%s",
					\t"events": [{ "event": "IMPRESSION", "collectionId": %d, "position": 0 }]
					}
					""".formatted(UUID.randomUUID(), collectionId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	@Test
	void emptyEventArrayIsRejected() throws Exception {
		long reporter = newMemberId();

		mockMvc.perform(post(EVENTS_URL).with(loginAs(reporter))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requestId\": \"" + UUID.randomUUID() + "\", \"events\": []}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void malformedRequestIdIsRejected() throws Exception {
		long reporter = newMemberId();

		mockMvc.perform(post(EVENTS_URL).with(loginAs(reporter))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requestId\": \"not-a-uuid\", \"events\": "
					+ "[{ \"event\": \"CLICK\", \"collectionId\": 1 }]}"))
			.andExpect(status().isBadRequest());
	}

	/** E8 — 같은 {@code request_id}의 중복 IMPRESSION은 패널티 집계에서 1회로 계산된다. */
	@Test
	void duplicateImpressionsInOneSessionCountOnce() throws Exception {
		long viewer = newMemberId();
		long collectionId = publishedCollection(newMemberId(), uniqueSeed("penalty"));
		UUID sharedRequestId = UUID.randomUUID();
		UUID otherRequestId = UUID.randomUUID();

		eventRepository.insertAll(List.of(
			impression(viewer, collectionId, sharedRequestId, 0),
			impression(viewer, collectionId, sharedRequestId, 1),
			impression(viewer, collectionId, otherRequestId, 0)));

		Map<Long, Integer> counts = eventRepository.countRecentImpressions(
			viewer, List.of(collectionId), Duration.ofDays(7));

		assertThat(counts).containsEntry(collectionId, 2);
	}

	/** 집계 윈도우 밖의 노출은 세지 않는다 — 그래야 감점이 영구히 남지 않는다. */
	@Test
	void impressionsOutsideTheWindowAreIgnored() throws Exception {
		long viewer = newMemberId();
		long collectionId = publishedCollection(newMemberId(), uniqueSeed("window"));
		UUID requestId = UUID.randomUUID();

		eventRepository.insertAll(List.of(impression(viewer, collectionId, requestId, 0)));
		jdbcTemplate.update(
			"UPDATE core.feed_event SET created_at = now() - INTERVAL '30 days' WHERE request_id = ?",
			requestId);

		assertThat(eventRepository.countRecentImpressions(
			viewer, List.of(collectionId), Duration.ofDays(7))).doesNotContainKey(collectionId);
	}

	@Test
	void countingWithNoCandidatesSkipsTheQuery() {
		assertThat(eventRepository.countRecentImpressions(1L, List.of(), Duration.ofDays(7))).isEmpty();
	}

	private FeedEventRow impression(long memberId, long collectionId, UUID requestId, int position) {
		return new FeedEventRow(memberId, collectionId, null, FeedEventType.IMPRESSION, requestId, position);
	}
}
