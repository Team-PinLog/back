package com.pinlog.pinlogback.domain.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.pinlog.pinlogback.domain.feed.dto.FeedCollectionsResponse;
import com.pinlog.pinlogback.domain.feed.dto.FeedEventCollectRequest;
import com.pinlog.pinlogback.domain.feed.dto.FeedEventItemRequest;
import com.pinlog.pinlogback.domain.feed.entity.FeedEventType;
import com.pinlog.pinlogback.domain.feed.repository.FeedCandidateRepository;
import com.pinlog.pinlogback.domain.feed.repository.FeedCollectionCard;
import com.pinlog.pinlogback.domain.feed.repository.FeedEventRepository;
import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordRepository;
import com.pinlog.pinlogback.global.exception.InvalidRequestException;

/**
 * 폴백 경로 검증. DB로는 재현하기 어려운 상황(후보 0건, Profile 계산 실패, 이벤트 기록 실패)을
 * 여기서 고정한다 — <b>Feed는 어떤 경우에도 AI·Cache 때문에 500을 내지 않는다</b>는 계약이
 * 이 클래스의 존재 이유다(feed-recommendation 5장).
 *
 * <p>공유 컨테이너를 쓰는 통합 테스트로는 "후보 0건"을 만들 수 없다 — 다른 테스트가 만든
 * Collection이 항상 후보에 들어오기 때문이다. 그래서 이 경로만 대역으로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedServiceTests {

	private static final long ME = 42L;

	@Mock
	private FeedCandidateRepository candidateRepository;

	@Mock
	private FeedKeywordRepository keywordRepository;

	@Mock
	private FeedEventRepository eventRepository;

	private FeedService feedService;

	@BeforeEach
	void setUp() {
		FeedProperties properties = FeedPropertiesFixture.defaults();
		feedService = new FeedService(candidateRepository, keywordRepository, eventRepository,
			new FeedScorer(properties), new FeedRanker(properties), properties);

		when(keywordRepository.findProfile(anyLong())).thenReturn(FeedProfile.empty());
		when(candidateRepository.findRecent(anyLong(), anyInt())).thenReturn(List.of());
		when(candidateRepository.findFollowed(anyLong(), anyInt())).thenReturn(List.of());
		when(candidateRepository.findRandomSample(anyLong(), anyInt(), anyLong())).thenReturn(List.of());
	}

	/** 후보 0건이어도 빈 목록으로 정상 응답한다. 오류가 아니다. */
	@Test
	void emptyCandidatePoolReturnsEmptyPage() {
		FeedCollectionsResponse response = feedService.recommend(ME, null, null);

		assertThat(response.items()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		assertThat(response.requestId()).isNotNull();
		verify(eventRepository, never()).insertAll(any());
	}

	/** 후보가 0건이면 특징·노출 조회를 아예 하지 않는다 — 빈 IN 절로 왕복하지 않는다. */
	@Test
	void emptyCandidatePoolSkipsFeatureQueries() {
		feedService.recommend(ME, null, null);

		verify(keywordRepository, never()).findPublicKeywordWeights(any());
		verify(eventRepository, never()).countRecentImpressions(anyLong(), any(), any());
		verify(candidateRepository, never()).findVerifiedCards(any());
	}

	/** D7 — Profile 계산이 실패해도 Cold Start로 폴백하고 응답은 정상이다. */
	@Test
	void profileFailureFallsBackToColdStart() {
		when(keywordRepository.findProfile(ME)).thenThrow(new IllegalStateException("ai 스키마 조회 실패"));

		assertThatCode(() -> feedService.recommend(ME, null, null)).doesNotThrowAnyException();
	}

	/** E2 — IMPRESSION 기록이 실패해도 Feed 응답은 정상이다. */
	@Test
	void impressionFailureDoesNotBreakTheResponse() {
		stubSingleCandidate();
		doThrow(new IllegalStateException("feed_event 기록 실패")).when(eventRepository).insertAll(any());

		FeedCollectionsResponse response = feedService.recommend(ME, null, null);

		assertThat(response.items()).hasSize(1);
		assertThat(response.items().get(0).keywords()).isEmpty();
	}

	/** 응답으로 나간 항목은 position과 함께 IMPRESSION으로 기록된다(E1). */
	@Test
	void respondedItemsAreRecordedAsImpressions() {
		stubSingleCandidate();

		FeedCollectionsResponse response = feedService.recommend(ME, null, null);

		verify(eventRepository).insertAll(List.of(new FeedEventRepository.FeedEventRow(
			ME, 7L, null, FeedEventType.IMPRESSION, response.requestId(), 0)));
	}

	/**
	 * 응답의 {@code keywords}는 점수 계산에 쓴 {@code code}가 아니라 {@code display_name}이다
	 * (08 §6.1). 정렬도 표시값 기준이다 — 화면에 보이는 순서가 곧 정렬 근거여야 한다.
	 */
	@Test
	void responseKeywordsAreDisplayNamesSortedByTheDisplayedLabel() {
		stubCandidateWithKeywords(Map.of("WALK", 0.5, "COFFEE_CHAT", 0.5));
		when(keywordRepository.findPublicDisplayNames(any()))
			.thenReturn(Map.of("WALK", "산책", "COFFEE_CHAT", "카페"));

		FeedCollectionsResponse response = feedService.recommend(ME, null, null);

		assertThat(response.items().get(0).keywords()).containsExactly("산책", "카페");
	}

	/**
	 * 표시값을 못 찾은 code는 <b>빠진다</b>. {@code code}로 대신 채우는 폴백을 두면 그 폴백이 곧
	 * 08 §6.1 위반이므로, 실패는 "영문이 뜬다"가 아니라 "덜 뜬다"여야 한다.
	 *
	 * <p>특징 집계와 표시값 조회는 별개의 쿼리다. 사이에 Preset이 폐기·차단되면 집계가 들고 온
	 * code를 표시값 쪽에서 못 찾는 상태가 실제로 만들어진다.
	 */
	@Test
	void aCodeWithoutAResolvableDisplayNameIsDroppedRatherThanShownAsCode() {
		stubCandidateWithKeywords(Map.of("WALK", 0.5, "RETIRED", 0.5));
		when(keywordRepository.findPublicDisplayNames(any())).thenReturn(Map.of("WALK", "산책"));

		FeedCollectionsResponse response = feedService.recommend(ME, null, null);

		assertThat(response.items().get(0).keywords()).containsExactly("산책");
	}

	/**
	 * 표시값 조회는 <b>요청당 한 번</b>이다. 항목마다 부르면 그대로 N+1이고, 프리셋이 27개뿐이라
	 * 개발 데이터에서는 증상이 드러나지 않는다(feed-tests N8).
	 */
	@Test
	void displayNamesAreResolvedOncePerRequestNotPerItem() {
		when(candidateRepository.findRecent(anyLong(), anyInt())).thenReturn(List.of(
			new FeedCandidate(7L, 9L, Instant.now(), false, false),
			new FeedCandidate(8L, 10L, Instant.now(), false, false)));
		when(keywordRepository.findPublicKeywordWeights(List.of(7L, 8L)))
			.thenReturn(Map.of(7L, Map.of("WALK", 1.0), 8L, Map.of("COFFEE_CHAT", 1.0)));
		when(eventRepository.countRecentImpressions(anyLong(), any(), any())).thenReturn(Map.of());
		when(candidateRepository.findVerifiedCards(any())).thenReturn(List.of(
			new FeedCollectionCard(7L, "책1", 3, Instant.now()),
			new FeedCollectionCard(8L, "책2", 3, Instant.now())));
		when(keywordRepository.findPublicDisplayNames(any()))
			.thenReturn(Map.of("WALK", "산책", "COFFEE_CHAT", "카페"));

		FeedCollectionsResponse response = feedService.recommend(ME, null, null);

		assertThat(response.items()).hasSize(2);
		verify(keywordRepository, times(1)).findPublicDisplayNames(any());
	}

	/** E3 — IMPRESSION은 서버가 기록하는 값이므로 클라이언트가 보내면 400이다. */
	@Test
	void clientReportedImpressionIsRejected() {
		FeedEventCollectRequest request = new FeedEventCollectRequest(UUID.randomUUID(),
			List.of(new FeedEventItemRequest(FeedEventType.IMPRESSION, 7L, null, 0)));

		assertThatThrownBy(() -> feedService.collect(ME, request))
			.isInstanceOf(InvalidRequestException.class);
		verify(eventRepository, never()).insertAll(any());
	}

	private void stubSingleCandidate() {
		stubCandidateWithKeywords(Map.of());
	}

	private void stubCandidateWithKeywords(Map<String, Double> weights) {
		when(candidateRepository.findRecent(anyLong(), anyInt()))
			.thenReturn(List.of(new FeedCandidate(7L, 9L, Instant.now(), false, false)));
		when(keywordRepository.findPublicKeywordWeights(List.of(7L)))
			.thenReturn(weights.isEmpty() ? Map.of() : Map.of(7L, weights));
		when(eventRepository.countRecentImpressions(anyLong(), any(), any())).thenReturn(Map.of());
		when(candidateRepository.findVerifiedCards(List.of(7L)))
			.thenReturn(List.of(new FeedCollectionCard(7L, "책", 3, Instant.now())));
	}
}
