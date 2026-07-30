package com.pinlog.pinlogback.domain.search.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.pinlog.pinlogback.domain.ai.client.AiSearchClient;
import com.pinlog.pinlogback.domain.ai.client.AiSearchResponse;
import com.pinlog.pinlogback.domain.ai.repository.ContextKeywordRepository;
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.search.dto.MatchedContextResponse;
import com.pinlog.pinlogback.domain.search.dto.RecordSearchItemResponse;
import com.pinlog.pinlogback.domain.search.dto.RecordSearchRequest;
import com.pinlog.pinlogback.domain.search.dto.RecordSearchResponse;
import com.pinlog.pinlogback.domain.search.dto.SearchPlaceResponse;
import com.pinlog.pinlogback.domain.search.repository.SearchRecordRepository;
import com.pinlog.pinlogback.domain.search.repository.VerifiedSearchRecord;
import com.pinlog.pinlogback.global.response.BoundsResponse;

/**
 * 개인 자연어 검색 유스케이스(API 명세 6.1, AI 설계 9장).
 *
 * <p>흐름은 넷이다. <b>FastAPI가 준 것을 그대로 내보내는 단계가 없다는 점이 핵심이다.</b>
 *
 * <ol>
 *   <li>FastAPI 호출 — Record 단위로 집계된 {@code (recordId, contextId, similarity)} 목록을 받는다</li>
 *   <li>Core 재검증 — 소유권·삭제·활성 Context·Place를 Spring이 다시 본다(9.5)</li>
 *   <li>조립 — 본문과 Keyword는 Core에서 조회해 붙인다. FastAPI는 본문을 주지 않는다</li>
 *   <li>bounds 계산 — <b>재검증을 통과한 것들</b>로만 계산한다</li>
 * </ol>
 *
 * <p><b>{@code @Transactional}을 붙이지 않았다.</b> 붙이면 FastAPI 호출(읽기 타임아웃 5s) 내내 DB
 * 커넥션이 잡혀 있는다 — AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md} 4.1이 금지하는
 * 바로 그 형태다. 이 메서드의 DB 조회 셋은 서로 다른 스냅샷을 봐도 무방하다: 그 사이에 무엇이
 * 지워지든 결과는 "그 항목이 빠진다" 쪽으로만 움직이고, 애초에 검색은 <b>움직이는 대상을 최선으로
 * 재검증</b>하는 일이라 한 스냅샷으로 묶는다고 더 정확해지지 않는다.
 */
@Service
public class RecordSearchService {

	private final AiSearchClient aiSearchClient;
	private final SearchRecordRepository searchRecordRepository;
	private final ContextRepository contextRepository;
	private final ContextKeywordRepository contextKeywordRepository;

	public RecordSearchService(AiSearchClient aiSearchClient, SearchRecordRepository searchRecordRepository,
		ContextRepository contextRepository, ContextKeywordRepository contextKeywordRepository) {
		this.aiSearchClient = aiSearchClient;
		this.searchRecordRepository = searchRecordRepository;
		this.contextRepository = contextRepository;
		this.contextKeywordRepository = contextKeywordRepository;
	}

	/**
	 * @param memberId 인증 경계에서 해석한 요청자(BD-14). 검색 범위이자 인가 기준이다
	 * @throws com.pinlog.pinlogback.domain.ai.exception.AiSearchException FastAPI 호출이 실패했을 때.
	 *     <b>빈 결과로 바꾸지 않는다</b> — 그러면 장애가 "일치하는 기록이 없음"으로 보인다
	 */
	public RecordSearchResponse search(long memberId, RecordSearchRequest request) {
		List<AiSearchResponse.Match> matches = distinctByRecord(
			aiSearchClient.search(memberId, request.query(), request.sizeOrDefault()));
		if (matches.isEmpty()) {
			return new RecordSearchResponse(null, List.of());
		}

		Map<Long, VerifiedSearchRecord> verified = searchRecordRepository.findVerified(
			matches.stream().map(AiSearchResponse.Match::recordId).toList(), memberId);
		Map<Long, Context> matchedContexts = contextRepository.findByIdInAndMemberId(
				matches.stream().map(AiSearchResponse.Match::contextId).toList(), memberId)
			.stream()
			.collect(Collectors.toMap(Context::getId, Function.identity()));
		Map<Long, List<String>> keywords =
			contextKeywordRepository.findKeywordsForOwner(List.copyOf(verified.keySet()), memberId);

		List<RecordSearchItemResponse> items = assemble(matches, verified, matchedContexts, keywords);
		return new RecordSearchResponse(
			BoundsResponse.enclosing(items, item -> item.place().lat(), item -> item.place().lng()),
			items);
	}

	/**
	 * FastAPI가 준 순서(유사도 내림차순)를 유지하며 통과한 것만 담는다.
	 *
	 * <p><b>탈락은 조용히 처리한다</b>(AI 파트 소유 명세 {@code ai-response-assembly.md} 6.3). 오류로
	 * 만들지 않고, 줄어든 개수를 다른 Record로 채우지도 않는다 — 채우면 "상위 N개"라는 정렬 계약이
	 * 깨지고, 오류로 만들면 남이 지운 기록 하나 때문에 내 검색 전체가 실패한다.
	 */
	private List<RecordSearchItemResponse> assemble(List<AiSearchResponse.Match> matches,
		Map<Long, VerifiedSearchRecord> verified, Map<Long, Context> matchedContexts,
		Map<Long, List<String>> keywords) {
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
