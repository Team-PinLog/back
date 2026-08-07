package com.pinlog.pinlogback.domain.search.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.domain.ai.KeywordResponseStatus;
import com.pinlog.pinlogback.domain.ai.client.AiSearchClient;
import com.pinlog.pinlogback.domain.ai.client.AiSearchResponse;
import com.pinlog.pinlogback.domain.ai.repository.ContextKeywordRepository;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.search.ConfidenceGateProperties;
import com.pinlog.pinlogback.domain.search.LexicalSearchProperties;
import com.pinlog.pinlogback.domain.search.dto.MatchedContextResponse;
import com.pinlog.pinlogback.domain.search.dto.RecordSearchItemResponse;
import com.pinlog.pinlogback.domain.search.dto.RecordSearchRequest;
import com.pinlog.pinlogback.domain.search.dto.RecordSearchResponse;
import com.pinlog.pinlogback.domain.search.dto.SearchPlaceResponse;
import com.pinlog.pinlogback.domain.search.repository.LexicalContextRepository;
import com.pinlog.pinlogback.domain.search.repository.SearchRecordRepository;
import com.pinlog.pinlogback.domain.search.repository.VerifiedSearchRecord;
import com.pinlog.pinlogback.global.response.BoundsResponse;

/**
 * 개인 자연어 검색 유스케이스(API 명세 6.1, AI 설계 9장).
 *
 * <p>흐름은 여섯이다. <b>FastAPI가 준 것을 그대로 내보내는 단계가 없다는 점이 핵심이다.</b>
 *
 * <ol>
 *   <li>FastAPI 호출 — Record 단위로 집계된
 *       {@code (recordId, contextId, similarity, keywordMatched)} 목록을 받는다</li>
 *   <li>문자열 병합 — 단어형 질의면 본문 문자열 매치를 합쳐 RRF로 재정렬한다(P49 §4, 기본 꺼짐).
 *       꺼져 있거나 실패하면 이 단계는 없던 것과 같다</li>
 *   <li>결합 신뢰도 게이트 — S1(벡터)·S2(문자열)·S3(키워드) 중 S1 하나뿐이고 유사도가 낮은 결과를
 *       뺀다(S15P11A705-400, 기본 꺼짐). 문자열·키워드 재정렬과는 <b>독립된 마지막 판단</b>이다</li>
 *   <li>Core 재검증 — 소유권·삭제·활성 Context·Place를 Spring이 다시 본다(9.5). 문자열 후보도
 *       똑같이 지난다</li>
 *   <li>조립 — 본문·Keyword·판정 상태는 Core에서 조회해 붙인다. FastAPI는 본문을 주지 않는다</li>
 *   <li>bounds 계산 — <b>재검증을 통과한 것들</b>로만 계산한다</li>
 * </ol>
 *
 * <p><b>{@code @Transactional}을 붙이지 않았다.</b> 붙이면 FastAPI 호출(읽기 타임아웃 5s) 내내 DB
 * 커넥션이 잡혀 있는다 — AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 4.1이 금지하는
 * 바로 그 형태다. 이 메서드의 DB 조회들은 서로 다른 스냅샷을 봐도 무방하다: 그 사이에 무엇이
 * 지워지든 결과는 "그 항목이 빠진다" 쪽으로만 움직이고, 애초에 검색은 <b>움직이는 대상을 최선으로
 * 재검증</b>하는 일이라 한 스냅샷으로 묶는다고 더 정확해지지 않는다.
 */
@Service
@EnableConfigurationProperties({LexicalSearchProperties.class, ConfidenceGateProperties.class})
public class RecordSearchService {

	private static final Logger log = LoggerFactory.getLogger(RecordSearchService.class);

	/** RRF 상수. 실측(ai 레포 I54)이 이 값으로 확정했고, 측정 도구와 같은 값이어야 비교가 성립한다. */
	private static final double RRF_K = 60.0;

	/**
	 * 문자열 단독 항목의 {@code similarity}. 이 항목은 벡터 컷을 통과하지 못해 코사인 값이 FastAPI
	 * 밖으로 나오지 않으므로 실을 원값이 없다. {@code null}은 응답 계약 위반이라(front 스키마가
	 * {@code number} 필수) 0.0을 싣는다 — front는 이 값을 UI에 노출하지 않고(API 명세 6.1), 0.0은
	 * 실제 코사인이 만들 수 없는 값이라 「문자열 매치로만 들어온 항목」의 표지 역할도 한다.
	 */
	private static final double LEXICAL_ONLY_SIMILARITY = 0.0;

	private final AiSearchClient aiSearchClient;
	private final SearchRecordRepository searchRecordRepository;
	private final ContextRepository contextRepository;
	private final ContextKeywordRepository contextKeywordRepository;
	private final LexicalContextRepository lexicalContextRepository;
	private final LexicalSearchProperties lexicalProperties;
	private final ConfidenceGateProperties gateProperties;

	public RecordSearchService(AiSearchClient aiSearchClient, SearchRecordRepository searchRecordRepository,
		ContextRepository contextRepository, ContextKeywordRepository contextKeywordRepository,
		LexicalContextRepository lexicalContextRepository, LexicalSearchProperties lexicalProperties,
		ConfidenceGateProperties gateProperties) {
		this.aiSearchClient = aiSearchClient;
		this.searchRecordRepository = searchRecordRepository;
		this.contextRepository = contextRepository;
		this.contextKeywordRepository = contextKeywordRepository;
		this.lexicalContextRepository = lexicalContextRepository;
		this.lexicalProperties = lexicalProperties;
		this.gateProperties = gateProperties;
	}

	/**
	 * @param memberId 인증 경계에서 해석한 요청자(BD-14). 검색 범위이자 인가 기준이다
	 * @throws com.pinlog.pinlogback.domain.ai.exception.AiSearchException FastAPI 호출이 실패했을 때.
	 *     <b>빈 결과로 바꾸지 않는다</b> — 그러면 장애가 "일치하는 기록이 없음"으로 보인다
	 */
	public RecordSearchResponse search(long memberId, RecordSearchRequest request) {
		List<AiSearchResponse.Match> vector =
			distinctByRecord(aiSearchClient.search(memberId, request.query(), request.sizeOrDefault()));
		LexicalMergeResult lexicalResult = mergeLexicalMatches(memberId, request, vector);
		List<AiSearchResponse.Match> matches =
			applyConfidenceGate(lexicalResult.matches(), lexicalResult.lexicalMatchedRecordIds());
		if (matches.isEmpty()) {
			return new RecordSearchResponse(null, List.of());
		}

		Map<Long, VerifiedSearchRecord> verified = searchRecordRepository.findVerified(
			matches.stream().map(AiSearchResponse.Match::recordId).toList(), memberId);
		Map<Long, Context> matchedContexts = contextRepository.findByIdInAndMemberId(
				matches.stream().map(AiSearchResponse.Match::contextId).toList(), memberId)
			.stream()
			.collect(Collectors.toMap(Context::getId, Function.identity()));
		List<Long> verifiedRecordIds = List.copyOf(verified.keySet());
		Map<Long, List<String>> keywords =
			contextKeywordRepository.findKeywordsForOwner(verifiedRecordIds, memberId);
		Map<Long, KeywordResponseStatus> keywordStatuses =
			contextKeywordRepository.findKeywordStatusForOwner(verifiedRecordIds, memberId);

		List<RecordSearchItemResponse> items =
			assemble(matches, verified, matchedContexts, keywords, keywordStatuses);
		return new RecordSearchResponse(
			BoundsResponse.enclosing(items, item -> item.place().lat(), item -> item.place().lng()),
			items);
	}

	/**
	 * {@link #mergeLexicalMatches}의 반환값. 병합된 목록과 함께 <b>어느 Record가 문자열로
	 * 매치됐는지</b>(S2 신호)를 실어 보낸다 — {@link #rrfMerge}가 병합 도중에만 알고 버리던 정보를
	 * 결합 신뢰도 게이트(S15P11A705-400)가 쓸 수 있게 한다.
	 *
	 * @param matches 병합된(또는 병합이 생략된) 목록
	 * @param lexicalMatchedRecordIds 문자열 매치가 있었던 Record id. 병합이 생략된 모든 경로에서는
	 *     빈 집합이다 — 그 경로들에서는 문자열 신호 자체가 계산되지 않았기 때문이다
	 */
	private record LexicalMergeResult(List<AiSearchResponse.Match> matches, Set<Long> lexicalMatchedRecordIds) {
	}

	/**
	 * 문자열 검색을 벡터 결과에 병합한다(P49 §4, 규칙의 실측 근거는 ai 레포 I54).
	 *
	 * <p>이 메서드가 벡터 결과를 그대로 돌려주는 경로가 셋이다 — 플래그 꺼짐, 단어형이 아닌 질의
	 * (게이트, P49 §5), 문자열 조회 실패. <b>어느 경로든 응답은 실패하지 않고 현행 검색과 같은
	 * 동작으로 되돌아간다</b>(P49 §4의 세 번째 원칙). 조회 실패를 오류로 올리지 않는 이유는 벡터
	 * 검색이 이미 성공해 있기 때문이다 — 보조 신호의 장애가 주 결과를 지우면 안 된다.
	 */
	private LexicalMergeResult mergeLexicalMatches(long memberId, RecordSearchRequest request,
		List<AiSearchResponse.Match> vector) {
		if (!lexicalProperties.enabled()) {
			return new LexicalMergeResult(vector, Set.of());
		}
		String query = stripSpaces(request.query());
		if (!isWordQuery(query)) {
			return new LexicalMergeResult(vector, Set.of());
		}
		List<LexicalContextRepository.LexicalMatch> lexical;
		try {
			lexical = lexicalContextRepository.findMatches(memberId, query, request.sizeOrDefault());
		} catch (RuntimeException e) {
			log.warn("lexical search failed; returning vector-only results", e);
			return new LexicalMergeResult(vector, Set.of());
		}
		if (lexical.isEmpty()) {
			return new LexicalMergeResult(vector, Set.of());
		}
		Set<Long> lexicalMatchedRecordIds = lexical.stream()
			.map(LexicalContextRepository.LexicalMatch::recordId)
			.collect(Collectors.toUnmodifiableSet());
		return new LexicalMergeResult(
			rrfMerge(vector, lexical, request.sizeOrDefault()), lexicalMatchedRecordIds);
	}

	/**
	 * 결합 신뢰도 게이트(S15P11A705-400, {@code OFFTOPIC-CONFIDENCE-GATE-HANDOFF-DRAFT.md} §4).
	 *
	 * <p>S1(벡터)·S2(문자열)·S3(키워드) 세 신호 중 <b>S1 하나뿐이고</b> 그 유사도가
	 * {@code similarityThreshold} 미만이면 결과에서 뺀다. S2·S3 중 하나라도 있으면 유사도와 무관하게
	 * 남긴다 — 문자열이든 키워드든 벡터 유사도가 못 잡는 근거가 따로 있다는 뜻이기 때문이다.
	 *
	 * <p>문자열 단독 항목({@code similarity == 0.0})은 애초에 {@code lexicalMatchedRecordIds}에
	 * 있으므로 이 규칙에서 자동으로 살아남는다 — 별도 분기가 필요 없다.
	 *
	 * <p>기존 재정렬·병합 계약(정렬은 후보를 추가·제거하지 않는다, P49 §4)은 이 게이트의 계약이
	 * 아니다 — 그 계약은 {@link #rrfMerge} 이전 단계인 키워드 재정렬(ai 레포 소관)의 것이고, 이
	 * 게이트는 그 뒤에 오는 <b>별도의 마지막 단계</b>다.
	 */
	private List<AiSearchResponse.Match> applyConfidenceGate(
		List<AiSearchResponse.Match> matches, Set<Long> lexicalMatchedRecordIds) {
		if (!gateProperties.enabled()) {
			return matches;
		}
		double threshold = gateProperties.similarityThreshold();
		return matches.stream()
			.filter(match -> lexicalMatchedRecordIds.contains(match.recordId())
				|| Boolean.TRUE.equals(match.keywordMatched())
				|| match.similarity() >= threshold)
			.toList();
	}

	/**
	 * 단어형인가 — 공백이 없고 짧을 때만 그렇다. ai 레포 {@code SearchService._is_word_query}와
	 * 같은 판정이어야 한다: 거기서 문장형으로 컷을 탄 질의가 여기서 단어형으로 문자열 경로를 타면
	 * 「단어형 한정」 게이트(I54)가 두 파트에서 서로 다른 질의 집합에 걸린다.
	 *
	 * <p>글자 수는 코드 포인트로 센다({@code String.length()}는 UTF-16 단위라 보충 평면 문자를 2로
	 * 센다). 공백 판정이 유니코드 공백 전체인 이유는 {@link #isQuerySpace(int)}에 있다.
	 */
	private boolean isWordQuery(String query) {
		return !query.isEmpty()
			&& query.codePointCount(0, query.length()) <= lexicalProperties.wordQueryMaxChars()
			&& query.codePoints().noneMatch(RecordSearchService::isQuerySpace);
	}

	/**
	 * 앞뒤 공백 정리. {@link String#strip()}을 쓰지 않는 이유는 그 판정({@code Character.isWhitespace})이
	 * NBSP(U+00A0)를 공백으로 보지 않아서다 — ai의 판정(Python {@code str.strip()}·{@code str.isspace()})은
	 * NBSP를 공백으로 보므로, 같은 질의가 두 파트에서 다르게 정리되면 안 된다.
	 */
	private static String stripSpaces(String raw) {
		int from = 0;
		int to = raw.length();
		while (from < to && isQuerySpace(raw.codePointAt(from))) {
			from += Character.charCount(raw.codePointAt(from));
		}
		while (to > from && isQuerySpace(raw.codePointBefore(to))) {
			to -= Character.charCount(raw.codePointBefore(to));
		}
		return raw.substring(from, to);
	}

	/**
	 * {@code isWhitespace}(탭·개행·전각 공백 등)와 {@code isSpaceChar}(NBSP 등 유니코드 공백 분류)의
	 * 합집합 — Python {@code str.isspace()}와 같은 범위를 덮는다. 좁게 잡으면 NBSP로 띄운 2어절
	 * 질의가 「공백 없음」으로 통과해 문장형 질의가 문자열 경로를 탄다(ai 쪽 같은 판정의 주석과
	 * 같은 근거).
	 */
	private static boolean isQuerySpace(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
	}

	/**
	 * 벡터 컷 통과자와 문자열 매치의 합집합을 RRF(k=60) 순위로 재정렬한다 — 실측으로 확정된
	 * 규칙이다(I54: 뒤에 추가하는 방식보다 정답 순위를 확실히 올렸다). 최종 절단은 RRF 순위 기준으로
	 * {@code limit}을 <b>한 번만</b> 적용한다(P49 §4-4 — 합집합이 {@code limit}을 넘으면 RRF
	 * 하위부터 잘린다는 것이 계약이다).
	 *
	 * <p>점수는 {@code Σ 1/(k + 목록 내 순위)}다. 벡터 목록의 순위는 FastAPI가 준 순서(유사도
	 * 내림차순), 문자열 목록의 순위는 {@code LexicalContextRepository}가 정한 순서다. 동점이면
	 * 벡터 순위가 있는 쪽·그 순위가 높은 쪽이 먼저이고 그다음 {@code recordId} 오름차순이다 —
	 * 측정 도구(ai 레포 {@code lexical_sweep.py})의 동점 규칙과 같아야 실측과 구현이 같은 순서를
	 * 낸다.
	 *
	 * <p>벡터에도 있는 Record는 원래 {@code Match}를 그대로 쓴다 — {@code similarity}는 원래
	 * 코사인 값을 유지하고 병합 점수는 노출하지 않는다(P49 §4-5). 문자열 단독 Record는 매치된
	 * Context를 대표로 {@code Match}를 새로 만든다.
	 */
	private List<AiSearchResponse.Match> rrfMerge(List<AiSearchResponse.Match> vector,
		List<LexicalContextRepository.LexicalMatch> lexical, int limit) {
		Map<Long, AiSearchResponse.Match> vectorByRecord = new LinkedHashMap<>();
		Map<Long, Integer> vectorRanks = new HashMap<>();
		for (AiSearchResponse.Match match : vector) {
			vectorByRecord.put(match.recordId(), match);
			vectorRanks.put(match.recordId(), vectorRanks.size() + 1);
		}
		Map<Long, Integer> lexicalRanks = new LinkedHashMap<>();
		Map<Long, Long> lexicalContexts = new HashMap<>();
		for (LexicalContextRepository.LexicalMatch match : lexical) {
			if (lexicalRanks.putIfAbsent(match.recordId(), lexicalRanks.size() + 1) == null) {
				lexicalContexts.put(match.recordId(), match.contextId());
			}
		}

		record Ranked(long recordId, double score, int vectorRank) {
		}
		List<Ranked> ranked = new ArrayList<>();
		for (Long recordId : union(vectorByRecord, lexicalRanks)) {
			Integer vectorRank = vectorRanks.get(recordId);
			Integer lexicalRank = lexicalRanks.get(recordId);
			double score = (vectorRank == null ? 0.0 : 1.0 / (RRF_K + vectorRank))
				+ (lexicalRank == null ? 0.0 : 1.0 / (RRF_K + lexicalRank));
			ranked.add(new Ranked(recordId, score, vectorRank == null ? Integer.MAX_VALUE : vectorRank));
		}
		ranked.sort(Comparator.comparingDouble((Ranked r) -> -r.score())
			.thenComparingInt(Ranked::vectorRank)
			.thenComparingLong(Ranked::recordId));

		List<AiSearchResponse.Match> merged = new ArrayList<>();
		for (Ranked entry : ranked.subList(0, Math.min(limit, ranked.size()))) {
			AiSearchResponse.Match fromVector = vectorByRecord.get(entry.recordId());
			merged.add(fromVector != null ? fromVector : new AiSearchResponse.Match(
				entry.recordId(), lexicalContexts.get(entry.recordId()), LEXICAL_ONLY_SIMILARITY, false));
		}
		return List.copyOf(merged);
	}

	/** 벡터 순서 먼저, 문자열 신규는 뒤에. 순서 자체는 정렬이 다시 정하므로 결정성만 맡는다. */
	private static List<Long> union(Map<Long, AiSearchResponse.Match> vectorByRecord,
		Map<Long, Integer> lexicalRanks) {
		List<Long> recordIds = new ArrayList<>(vectorByRecord.keySet());
		for (Long recordId : lexicalRanks.keySet()) {
			if (!vectorByRecord.containsKey(recordId)) {
				recordIds.add(recordId);
			}
		}
		return recordIds;
	}

	/**
	 * FastAPI가 준 순서(유사도 내림차순)를 유지하며 통과한 것만 담는다.
	 *
	 * <p><b>탈락은 조용히 처리한다</b>(AI 파트 소유 명세 {@code ai-response-assembly.md} 6.3). 오류로
	 * 만들지 않고, 줄어든 개수를 다른 Record로 채우지도 않는다 — 채우면 "상위 N개"라는 정렬 계약이
	 * 깨지고, 오류로 만들면 남이 지운 기록 하나 때문에 내 검색 전체가 실패한다.
	 *
	 * <p><b>{@code keywords}와 {@code keywordStatus}는 서로를 검사하지 않는다</b>(명세 5.1). 둘은
	 * 다른 쿼리에서 오고 그 사이에 판정이 끝날 수 있다 — {@code PROCESSING}인데 배열이 차 있거나
	 * 그 반대인 조합이 정상적으로 나온다. 여기서 "맞춰" 주면 <b>둘 중 하나를 조용히 거짓으로
	 * 만드는</b> 셈이고, 어느 쪽을 고쳐도 사용자에게는 그 순간 사실이던 값이 사라진다.
	 */
	private List<RecordSearchItemResponse> assemble(List<AiSearchResponse.Match> matches,
		Map<Long, VerifiedSearchRecord> verified, Map<Long, Context> matchedContexts,
		Map<Long, List<String>> keywords, Map<Long, KeywordResponseStatus> keywordStatuses) {
		List<RecordSearchItemResponse> items = new ArrayList<>(matches.size());
		for (AiSearchResponse.Match match : matches) {
			VerifiedSearchRecord record = verified.get(match.recordId());
			Context context = matchedContexts.get(match.contextId());
			if (record == null || context == null || !context.getRecordId().equals(match.recordId())) {
				continue;
			}
			items.add(new RecordSearchItemResponse(
				record.recordId(),
				match.similarity(),
				new SearchPlaceResponse(record.placeId(), record.placeName(), record.placeAddress(),
					record.lat(), record.lng()),
				MatchedContextResponse.from(context),
				keywords.getOrDefault(record.recordId(), List.of()),
				keywordStatuses.getOrDefault(record.recordId(), KeywordResponseStatus.COMPLETED),
				record.createdAt()));
		}
		return List.copyOf(items);
	}

	/**
	 * 한 Record는 한 번만 나온다(AI 설계 9.4). FastAPI가 이미 {@code DISTINCT ON (record_id)}로
	 * 집계하지만 <b>Spring도 이중으로 보장한다</b>(응답 조립 명세 6.3). 앞선 것이 유사도가 높으므로
	 * 먼저 온 것을 남기며, 그것이 곧 대표 {@code matchedContext}다.
	 *
	 * <p>항목 자체가 {@code null}이거나 {@code recordId}·{@code contextId}·{@code similarity}가 비어 온
	 * 항목은 버린다. 계약상 올 수 없는 형태지만, 여기서 걸러 두지 않으면 아래 조립에서
	 * {@code NullPointerException}이 되어 상대 응답의 결함이 우리 500으로 나타난다.
	 *
	 * <p><b>{@code match == null}까지 보는 이유</b>는 방어 층을 맞추는 것이다.
	 * {@link com.pinlog.pinlogback.domain.ai.client.AiSearchClient}가 최상위 {@code results == null}을
	 * 이미 방어하는데 그것도 계약상으로는 똑같이 올 수 없는 형태다 — 최상위는 믿지 않고 원소는 믿으면
	 * 이 메서드가 약속한 범위와 실제 방어 범위가 어긋난다.
	 */
	private List<AiSearchResponse.Match> distinctByRecord(List<AiSearchResponse.Match> matches) {
		Map<Long, AiSearchResponse.Match> best = new LinkedHashMap<>();
		for (AiSearchResponse.Match match : matches) {
			if (match == null || match.recordId() == null || match.contextId() == null
				|| match.similarity() == null) {
				continue;
			}
			best.putIfAbsent(match.recordId(), match);
		}
		return List.copyOf(best.values());
	}
}
