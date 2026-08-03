package com.pinlog.pinlogback.domain.feed;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.pinlog.pinlogback.global.response.CursorPage;

import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/core/v1/feed/collections}의 요청·응답 계약(feed-tests 11장 Q1~Q7,
 * 6장 P1·P2). 명세에만 두면 구현에서 빠지므로 테스트로 못박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedApiTests extends FeedFixtures {

	/** Q1 — {@code size} 없이 호출하면 공통 기본값 20이다. Feed 전용 기본값을 두지 않는다. */
	@Test
	void sizeDefaultsToCommonPageSize() throws Exception {
		long viewer = newMemberId();
		givenPublishedCollectionsByDistinctOwners(CursorPage.DEFAULT_SIZE + 1);

		mockMvc.perform(get(FEED_URL).with(loginAs(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(CursorPage.DEFAULT_SIZE));
	}

	/** Q2 — 상한을 넘는 {@code size}는 400이 아니라 {@code normalizeSize}가 100으로 보정한다. */
	@Test
	void sizeAboveMaxIsClampedNotRejected() throws Exception {
		long viewer = newMemberId();
		givenPublishedCollectionsByDistinctOwners(3);

		JsonNode response = feed(viewer, "?size=500");

		assertThat(collectionIdsOf(response).size()).isLessThanOrEqualTo(CursorPage.MAX_SIZE);
	}

	/** Q3 — 0 이하의 {@code size}는 기본값으로 보정된다. */
	@Test
	void sizeAtOrBelowZeroFallsBackToDefault() throws Exception {
		long viewer = newMemberId();
		givenPublishedCollectionsByDistinctOwners(CursorPage.DEFAULT_SIZE + 1);

		mockMvc.perform(get(FEED_URL).param("size", "0").with(loginAs(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(CursorPage.DEFAULT_SIZE));
	}

	/** Q4·K10 — 커서를 그대로 넘기면 같은 Session의 다음 페이지가 오고 항목이 겹치지 않는다. */
	@Test
	void cursorRoundTripKeepsSessionAndAvoidsOverlap() throws Exception {
		long viewer = newMemberId();
		givenPublishedCollectionsByDistinctOwners(12);

		JsonNode first = feed(viewer, "?size=5");
		String nextCursor = first.at("/data/nextCursor").asString();
		JsonNode second = feed(viewer, "?size=5&cursor=" + nextCursor);

		assertThat(first.at("/data/hasNext").asBoolean()).isTrue();
		assertThat(second.at("/data/requestId").asString())
			.isEqualTo(first.at("/data/requestId").asString());
		assertThat(collectionIdsOf(second)).doesNotContainAnyElementsOf(collectionIdsOf(first));
	}

	/** Q7 — {@code requestId}는 {@code data} 안의 별도 필드다. 항목마다 반복하지 않는다. */
	@Test
	void requestIdIsASingleFieldNextToItems() throws Exception {
		long viewer = newMemberId();
		givenPublishedCollectionsByDistinctOwners(2);

		JsonNode response = feed(viewer, "?size=2");

		assertThat(UUID.fromString(response.at("/data/requestId").asString())).isNotNull();
		response.at("/data/items").forEach(item ->
			assertThat(item.has("requestId")).isFalse());
	}

	/** 페이지마다 항목의 {@code position}은 0부터 이어진다 — 클라이언트가 그대로 돌려보내는 값이다. */
	@Test
	void itemsCarryZeroBasedPosition() throws Exception {
		long viewer = newMemberId();
		givenPublishedCollectionsByDistinctOwners(3);

		mockMvc.perform(get(FEED_URL).param("size", "3").with(loginAs(viewer)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].position").value(0))
			.andExpect(jsonPath("$.data.items[1].position").value(1))
			.andExpect(jsonPath("$.data.items[2].position").value(2));
	}

	/** P1·P2 — 응답에 Context 원문도, 소유자 식별 정보도 없다. */
	@Test
	void responseCarriesNoOwnerIdentityAndNoContextBody() throws Exception {
		long viewer = newMemberId();
		givenPublishedCollectionsByDistinctOwners(3);

		String payload = mockMvc.perform(get(FEED_URL).param("size", "20").with(loginAs(viewer)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(payload)
			.doesNotContain("memberId")
			.doesNotContain("ownerId")
			.doesNotContain("email")
			.doesNotContain("피드 후보용 맥락");
	}

	/** 21번 시나리오 — AI 미완료 Collection도 후보에 들어오고 {@code keywords}는 빈 배열이다. */
	@Test
	void collectionWithoutAiKeywordsIsIncludedWithEmptyKeywords() throws Exception {
		long viewer = newMemberId();
		long owner = newMemberId();
		long collectionId = publishedCollection(owner, uniqueSeed("no-keyword"));

		JsonNode response = feed(viewer, "?size=" + CursorPage.MAX_SIZE);

		JsonNode target = itemOf(response, collectionId);
		assertThat(target).isNotNull();
		assertThat(target.at("/keywords").isEmpty()).isTrue();
		assertThat(target.at("/title").asString()).isNotBlank();
		assertThat(target.at("/recordCount").asInt()).isPositive();
	}

	/** Q6 — 위조된 커서는 400이다. 500이 아니다. */
	@Test
	void tamperedCursorIsBadRequestNotServerError() throws Exception {
		long viewer = newMemberId();

		mockMvc.perform(get(FEED_URL).param("cursor", "!!!broken!!!").with(loginAs(viewer)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	@Test
	void unauthenticatedRequestIsRejected() throws Exception {
		mockMvc.perform(get(FEED_URL))
			.andExpect(status().isUnauthorized());
	}

	private void givenPublishedCollectionsByDistinctOwners(int count) throws Exception {
		// 소유자를 나누는 이유: 한 소유자로 몰면 다양성 조정(소유자당 2건)이 개수를 줄여
		// 페이지 크기 계약을 검증할 수 없다.
		List<Integer> indexes = IntStream.range(0, count).boxed().toList();
		for (int index : indexes) {
			publishedCollection(newMemberId(), uniqueSeed("feed-api-" + index));
		}
	}

}
