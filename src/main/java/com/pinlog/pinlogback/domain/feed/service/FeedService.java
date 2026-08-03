package com.pinlog.pinlogback.domain.feed.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.domain.feed.dto.FeedCollectionItemResponse;
import com.pinlog.pinlogback.domain.feed.dto.FeedCollectionsResponse;
import com.pinlog.pinlogback.domain.feed.dto.FeedEventCollectRequest;
import com.pinlog.pinlogback.domain.feed.dto.FeedEventItemRequest;
import com.pinlog.pinlogback.domain.feed.entity.FeedEventType;
import com.pinlog.pinlogback.domain.feed.repository.FeedCandidateRepository;
import com.pinlog.pinlogback.domain.feed.repository.FeedCollectionCard;
import com.pinlog.pinlogback.domain.feed.repository.FeedEventRepository;
import com.pinlog.pinlogback.domain.feed.repository.FeedEventRepository.FeedEventRow;
import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordLabel;
import com.pinlog.pinlogback.domain.feed.repository.FeedKeywordRepository;
import com.pinlog.pinlogback.global.exception.InvalidRequestException;
import com.pinlog.pinlogback.global.response.CursorPage;

import lombok.extern.slf4j.Slf4j;

/**
 * Feed 추천 파이프라인(feed-recommendation 3장). <b>Spring 단독 기능이다</b> — 요청 처리 중
 * FastAPI·Embedding·LLM을 호출하지 않고 벡터 유사도를 계산하지 않는다. 이미 저장된 AI 파생
 * 데이터만 읽는다.
 *
 * <pre>
 * Profile 조회 → 후보 생성(3채널) → 특징 조회 → 점수 계산 → 다양성 조정
 *              → Core 상태 재검증 → 응답 조립 → IMPRESSION 기록
 * </pre>
 *
 * <p>{@code @Transactional}을 걸지 않는다. 전 과정이 읽기이고 마지막 IMPRESSION 기록은
 * <b>실패해도 응답이 정상이어야</b> 하므로, 하나의 트랜잭션에 묶으면 이벤트 기록 실패가 응답을
 * 함께 되돌린다(feed-recommendation 5장).
 */
@Slf4j
@Service
@EnableConfigurationProperties(FeedProperties.class)
public class FeedService {

	private final FeedCandidateRepository candidateRepository;
	private final FeedKeywordRepository keywordRepository;
	private final FeedEventRepository eventRepository;
	private final FeedScorer scorer;
	private final FeedRanker ranker;
	private final FeedProperties properties;

	public FeedService(FeedCandidateRepository candidateRepository, FeedKeywordRepository keywordRepository,
		FeedEventRepository eventRepository, FeedScorer scorer, FeedRanker ranker,
		FeedProperties properties) {
		this.candidateRepository = candidateRepository;
		this.keywordRepository = keywordRepository;
		this.eventRepository = eventRepository;
		this.scorer = scorer;
		this.ranker = ranker;
		this.properties = properties;
	}

	/**
	 * 추천 목록 한 페이지(API 명세 10.1).
	 *
	 * @param cursor 이전 응답의 {@code nextCursor}. 없으면 새 Session을 시작한다
	 * @param size 공통 커서 계약을 그대로 따른다 — Feed 전용 상한을 두지 않는다(feed-tests Q1~Q3)
	 */
	public FeedCollectionsResponse recommend(long memberId, String cursor, Integer size) {
		int pageSize = CursorPage.normalizeSize(size);
		FeedSession session = FeedSession.resolve(cursor);
		RankedFeed ranked = rank(memberId, session);

		int consumed = Math.min(session.offset(), ranked.order().size());
		List<FeedCollectionItemResponse> items = new ArrayList<>(pageSize);
		while (items.size() < pageSize && consumed < ranked.order().size()) {
			int end = Math.min(consumed + (pageSize - items.size()), ranked.order().size());
			// 재검증에서 탈락한 만큼은 다음 후보로 채운다. 채운 항목도 같은 재검증을 통과한
			// 것이어야 한다 — 재검증 없이 채우면 방어선이 뚫린다(feed-profile-cache 6.2).
			for (FeedCollectionCard card : candidateRepository.findVerifiedCards(
				ranked.order().subList(consumed, end))) {
				items.add(FeedCollectionItemResponse.of(items.size(), card, ranked.keywordsOf(card)));
			}
			consumed = end;
		}

		boolean hasNext = consumed < ranked.order().size();
		CursorPage<FeedCollectionItemResponse> page = hasNext
			? CursorPage.of(items, session.encodeCursorAt(consumed))
			: CursorPage.last(items);
		recordImpressions(memberId, session.requestId(), items);
		return FeedCollectionsResponse.of(session.requestId(), page);
	}

	/**
	 * CLICK·SAVE 수집(API 명세 10.2). 쓰기 전용이며 어떤 조회 결과도 반환하지 않는다.
	 */
	public void collect(long memberId, FeedEventCollectRequest request) {
		if (request.events().stream().anyMatch(event -> !event.event().isClientReportable())) {
			throw new InvalidRequestException(
				"IMPRESSION은 서버가 기록하는 이벤트이므로 클라이언트가 보고할 수 없습니다.");
		}
		List<Long> requested = request.events().stream()
			.map(FeedEventItemRequest::collectionId)
			.distinct()
			.toList();
		// 실재하지 않거나 삭제된 Collection의 이벤트만 버리고 나머지는 저장한다. 부분 실패로
		// 요청 전체를 실패시키지 않는다(feed-event 4장).
		Set<Long> existing = Set.copyOf(candidateRepository.findExistingIds(requested));
		List<FeedEventRow> rows = request.events().stream()
			.filter(event -> existing.contains(event.collectionId()))
			.map(event -> new FeedEventRow(memberId, event.collectionId(), event.placeId(),
				event.event(), request.requestId(), event.position()))
			.toList();
		eventRepository.insertAll(rows);
	}

	/**
	 * 후보 생성부터 다양성 조정까지. 커서로 이어받은 페이지도 이 계산을 다시 수행하지만,
	 * 무작위 채널의 seed가 {@code requestId}에서 나오고 점수·배치가 결정적이므로 같은 순서가
	 * 복원된다 — 그래서 페이지 간 중복·누락이 생기지 않는다.
	 */
	private RankedFeed rank(long memberId, FeedSession session) {
		FeedProfile profile = loadProfile(memberId);
		boolean coldStart = profile.isColdStart(properties.coldStart().threshold());

		List<FeedCandidate> candidates = collectCandidates(memberId, session.seed());
		if (candidates.isEmpty()) {
			return new RankedFeed(List.of(), Map.of(), Map.of());
		}
		List<Long> ids = candidates.stream().map(FeedCandidate::collectionId).toList();
		Map<Long, Map<String, Double>> keywords = keywordRepository.findPublicKeywordWeights(ids);
		Map<Long, Integer> impressions = eventRepository.countRecentImpressions(
			memberId, ids, properties.scoring().impressionWindow());

		List<ScoredCandidate> scored = scorer.score(
			candidates, profile, keywords, impressions, coldStart, Instant.now());
		return new RankedFeed(ranker.arrange(scored, coldStart), keywords, labelsOf(keywords));
	}

	/**
	 * 후보 전체의 code를 모아 표시 정보를 <b>한 번에</b> 조회한다(feed-tests N8). 서로 다른 code는
	 * 프리셋 수(27)를 넘지 않으므로 후보가 200건이어도 IN 절 크기가 그만큼 커지지 않는다.
	 *
	 * <p>응답에 실릴 항목만 골라 조회하지 않는 것은 의도적이다 — 재검증 탈락분을 다음 후보로
	 * 채우는 루프가 응답 조립 중에 새 id를 끌어오므로, 그 시점에 조회하면 페이지를 채울 때마다
	 * 왕복이 늘어난다.
	 */
	private Map<String, FeedKeywordLabel> labelsOf(Map<Long, Map<String, Double>> keywords) {
		Set<String> codes = keywords.values().stream()
			.flatMap(weights -> weights.keySet().stream())
			.collect(Collectors.toSet());
		return keywordRepository.findPublicKeywordLabels(codes);
	}

	/**
	 * Profile 계산 실패는 오류가 아니라 Cold Start 폴백이다(feed-recommendation 5장).
	 * Feed는 어떤 경우에도 AI 파생 데이터 때문에 500을 내지 않는다.
	 */
	private FeedProfile loadProfile(long memberId) {
		try {
			return keywordRepository.findProfile(memberId);
		} catch (RuntimeException e) {
			log.warn("Feed Profile 계산에 실패해 Cold Start로 폴백합니다. memberId={}", memberId, e);
			return FeedProfile.empty();
		}
	}

	/**
	 * 세 채널의 합집합(feed-scoring 2.3). 중복은 페널티가 아니라 <b>신호</b>이므로 제거하되 출처는
	 * 보존한다 — 팔로우 채널 출처 여부가 점수 공식의 {@code followSignal}이 된다.
	 *
	 * <p>삽입 순서가 곧 잘라내기 우선순위다(팔로우 → 최신 → 무작위). 현재 배분에서는 합이
	 * {@code pool-size}와 같아 잘라내기가 발동하지 않지만, 배분을 올렸을 때의 방어선으로 남긴다.
	 */
	private List<FeedCandidate> collectCandidates(long memberId, long seed) {
		FeedProperties.Candidate config = properties.candidate();
		Map<Long, FeedCandidate> merged = new LinkedHashMap<>();
		List.of(
				candidateRepository.findFollowed(memberId, config.followLimit()),
				candidateRepository.findRecent(memberId, config.recentLimit()),
				candidateRepository.findRandomSample(memberId, config.randomLimit(), seed))
			.forEach(channel -> channel.forEach(candidate ->
				merged.merge(candidate.collectionId(), candidate, FeedCandidate::mergedWith)));

		List<FeedCandidate> pool = List.copyOf(merged.values());
		return pool.size() <= config.poolSize() ? pool : pool.subList(0, config.poolSize());
	}

	/**
	 * 응답으로 내보낸 항목을 IMPRESSION으로 기록한다(feed-event 3.1). 이 기록이 다음 요청의
	 * 노출 패널티 근거가 된다.
	 *
	 * <p>기록 실패는 로그만 남기고 응답은 정상 반환한다 — 관측 이벤트 유실이 사용자 경험을
	 * 해치지 않는다. 명세가 말하는 별도 스레드 수행은 아직 하지 않는다(후속).
	 */
	private void recordImpressions(long memberId, UUID requestId, List<FeedCollectionItemResponse> items) {
		if (items.isEmpty()) {
			return;
		}
		try {
			eventRepository.insertAll(items.stream()
				.map(item -> new FeedEventRow(memberId, item.collectionId(), null,
					FeedEventType.IMPRESSION, requestId, item.position()))
				.toList());
		} catch (RuntimeException e) {
			log.warn("Feed IMPRESSION 기록에 실패했습니다. requestId={}", requestId, e);
		}
	}

	/**
	 * 정렬이 끝난 id 순서와, 그 후보들의 {@code PUBLIC} Keyword 분포, 그리고 그 분포에 등장하는
	 * {@code code}의 화면 표시값.
	 *
	 * <p>응답의 {@code keywords}를 {@code keywords} Map에서 꺼내는 것이 중요하다 — 점수 계산에 쓴
	 * 것과 같은 값이므로, 응답에만 다른 가시성 필터가 적용될 경로가 없다. 표시값 조회는 그
	 * 결과에서 <b>파생</b>될 뿐 대상을 새로 고르지 않는다.
	 *
	 * @param labels Keyword {@code code} → 표시 정보. 점수 계산은 {@code code}로 하고 옮기는 것은
	 *     여기 한 곳뿐이다(08 §6.1, S15P11A705-252)
	 */
	private record RankedFeed(List<Long> order, Map<Long, Map<String, Double>> keywords,
		Map<String, FeedKeywordLabel> labels) {

		/**
		 * 응답에 실을 Keyword. 최대 {@link FeedCollectionItemResponse#KEYWORD_LIMIT}개이며 Keyword가
		 * 없으면 빈 배열이고 오류가 아니다(feed-recommendation 3.7).
		 *
		 * <p>표시값을 못 찾은 {@code code}는 <b>버린다</b>. {@code code}로 대신 채우면 그 폴백이 곧
		 * 08 §6.1 위반이므로, Preset이 도중에 폐기·차단됐을 때의 실패는 "영문이 뜬다"가 아니라
		 * "덜 뜬다"여야 한다.
		 *
		 * <p>정렬 기준은 {@link FeedKeywordSelector}가 갖는다 — <b>Collection 내재 기준이지 보는
		 * 사람 기준이 아니다.</b> 여기서 추천 점수를 끌어다 쓰면 같은 Collection이 사람마다 다른
		 * Keyword를 보여주고, 남의 카드에 내 Profile이 비친다(P46).
		 */
		List<String> keywordsOf(FeedCollectionCard card) {
			return FeedKeywordSelector.select(
				keywords.getOrDefault(card.collectionId(), Map.of()), labels,
				FeedCollectionItemResponse.KEYWORD_LIMIT);
		}
	}
}
