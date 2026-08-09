package com.pinlog.pinlogback.domain.ai.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.pinlog.pinlogback.domain.ai.AiProperties;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

/**
 * {@code POST /internal/v1/search/judge} 호출 — 검색 4번째 신호(LLM 관련도 재판정).
 *
 * <p>{@link AiSearchClient}와 실패 정책이 정반대다. 저쪽은 주 신호라 모든 실패를 예외로 올리지만,
 * 이 클라이언트는 <b>보조 신호</b>다. 그래서 실패를 여기서 흡수하지 않고 그대로 던진다 —
 * {@code RecordSearchService.judgeRelevance()}가 {@code mergeLexicalMatches()}와 같은 강등
 * 패턴(플래그 → 게이트 → try/catch 흡수)으로 받아, 실패하면 판정 이전 순서를 그대로 쓴다.
 *
 * <p>재시도하지 않는다 — 사용자 요청 경로이고, 판정 실패는 어차피 원 순서로 강등되므로 재시도로
 * 얻는 값이 없다(오히려 요청 스레드만 더 붙잡는다).
 */
@Component
public class AiRelevanceJudgeClient {

	private static final String PATH = "/internal/v1/search/judge";
	private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	private final RestClient restClient;
	private final String internalSecret;

	public AiRelevanceJudgeClient(@Qualifier("aiJudgeRestClient") RestClient aiJudgeRestClient,
		AiProperties properties) {
		this.restClient = aiJudgeRestClient;
		this.internalSecret = Objects.requireNonNullElse(properties.internalSecret(), "");
	}

	/**
	 * @param candidates 3신호 병합까지 끝난 최종 후보(본문 포함)
	 * @throws org.springframework.web.client.RestClientException 호출이 실패했을 때. <b>여기서
	 *     삼키지 않는다</b> — 흡수는 호출부의 책임이다
	 */
	public List<AiRelevanceJudgeResponse.Judgment> judge(String query,
		List<AiRelevanceJudgeRequest.Candidate> candidates) {
		AiRelevanceJudgeRequest request = new AiRelevanceJudgeRequest(query, candidates);
		AiRelevanceJudgeResponse response = restClient.post()
			.uri(PATH)
			.header(INTERNAL_SECRET_HEADER, internalSecret)
			.header(REQUEST_ID_HEADER, currentRequestId())
			.body(request)
			.retrieve()
			.body(AiRelevanceJudgeResponse.class);
		return response == null || response.results() == null ? List.of() : response.results();
	}

	/** 요청 스레드에서 동기로 도므로 MDC에 traceId가 있다. 없으면 새로 만든다. */
	private String currentRequestId() {
		String traceId = MDC.get(TraceIdFilter.TRACE_ID);
		return traceId != null ? traceId : UUID.randomUUID().toString();
	}
}
