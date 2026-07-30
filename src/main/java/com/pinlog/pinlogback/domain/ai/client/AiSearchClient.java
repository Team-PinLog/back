package com.pinlog.pinlogback.domain.ai.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.pinlog.pinlogback.domain.ai.AiProperties;
import com.pinlog.pinlogback.domain.ai.exception.AiSearchException;
import com.pinlog.pinlogback.global.web.TraceIdFilter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /internal/v1/search} 호출. AI 파트 소유 명세 {@code docs/ai/spec/ai-integration.md}
 * 2·3장을 따른다.
 *
 * <p><b>{@link AiProcessClient}와 실패 정책이 정반대다.</b> 저쪽은 모든 실패를 삼키고 {@code PENDING}에
 * 뒷일을 맡기지만, 검색에는 뒷수습이 없다. 삼키면 사용자에게 빈 결과가 보이고 그것은 "일치하는
 * 기록이 없음"과 구분되지 않는다 — 설정이 어긋난 채 배포된 사실을 아무도 모르게 된다
 * (ai 레포 {@code docs/spec/model-profile.md} 3.1). 그래서 <b>모든 실패를 예외로 올린다.</b>
 *
 * <p><b>재시도하지 않는다.</b> 사용자 요청 경로라 기다릴수록 손해이고, FastAPI 장애 시 재시도는
 * 요청 스레드 점유만 늘려 Core 처리량까지 끌어내린다(명세 3장).
 *
 * <p>{@code @Qualifier}가 필요한 이유: {@link RestClient} Bean이 {@code process}용과 둘이라
 * 타입만으로는 고를 수 없다.
 */
@Component
public class AiSearchClient {

	private static final Logger log = LoggerFactory.getLogger(AiSearchClient.class);

	private static final String PATH = "/internal/v1/search";
	/** ai 레포 {@code app/core/security.py::INTERNAL_SECRET_HEADER}가 실행 가능한 계약의 원본이다. */
	private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";
	private static final String REQUEST_ID_HEADER = "X-Request-Id";
	/** Profile 불일치 응답에만 실리는 필드(ai 레포 {@code app/main.py}의 예외 핸들러). */
	private static final String SERVER_PROFILE_FIELD = "serverProfile";
	private static final String REQUEST_PROFILE_FIELD = "requestProfile";
	private static final String PROD_PROFILE = "prod";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final String internalSecret;
	private final String embeddingProfile;

	/**
	 * <b>시크릿과 Profile을 다르게 다룬다.</b>
	 *
	 * <p>시크릿이 비었을 때 기동을 실패시키는 검사는 여기 두지 않는다. 같은 설정 키를 읽는
	 * {@link AiProcessClient}가 이미 운영 프로파일에서 던지므로, 두 번 검사해도 막을 수 있는 상태가
	 * 늘지 않고 실패 메시지만 둘이 된다. 검색 쪽 401은 어차피 조용하지 않다 — 오류 응답이 된다.
	 *
	 * <p>{@code embedding-profile}에는 그 논거가 적용되지 않는다. <b>이 클라이언트만 읽는 키라</b>
	 * 대신 검사해 주는 곳이 없다. 그래서 {@link #requireEmbeddingProfile}로 기동 시점에 끊는다.
	 */
	public AiSearchClient(@Qualifier("aiSearchRestClient") RestClient aiSearchRestClient,
		ObjectMapper objectMapper, AiProperties properties, Environment environment) {
		this.restClient = aiSearchRestClient;
		this.objectMapper = objectMapper;
		this.internalSecret = Objects.requireNonNullElse(properties.internalSecret(), "");
		this.embeddingProfile = requireEmbeddingProfile(properties.embeddingProfile(), environment);
	}

	/**
	 * Profile이 없을 때의 동작을 {@link AiProcessClient}의 시크릿 검사와 같은 기준으로 가른다 —
	 * 운영은 기동 실패, 그 외는 경고.
	 *
	 * <p><b>{@code application.yml}의 기본값이 이 검사를 대신하지 못한다.</b> 기본값은 변수를
	 * <b>설정하지 않은</b> 경우만 막는다. {@code PINLOG_AI_EMBEDDING_PROFILE=}처럼 빈 값으로 정의하면
	 * 빈 문자열이 기본값을 이기고, 그러면 FastAPI가 자기 Profile과 대조해 422를 주므로 결과는
	 * <b>모든 검색이 503</b>이다. 이 저장소는 같은 형태를 한 번 겪었다(BT-05 — {@code .env.example}이
	 * 자격증명을 빈 값으로 정의해 무관한 테스트가 컨텍스트 실패한 건).
	 *
	 * <p>검사를 두는 값어치는 <b>발견 시점</b>이다. 없으면 배포 스모크나 첫 사용자 검색까지 아무도
	 * 모른다.
	 */
	private static String requireEmbeddingProfile(String profile, Environment environment) {
		if (profile != null && !profile.isBlank()) {
			return profile;
		}
		if (environment.matchesProfiles(PROD_PROFILE)) {
			throw new IllegalStateException(
				"운영 프로파일에는 pinlog.ai.embedding-profile(PINLOG_AI_EMBEDDING_PROFILE)이 필요하다. "
					+ "값이 비면 FastAPI가 자기 Profile과 대조해 422로 거절하므로 모든 검색이 503이 된다");
		}
		log.warn("pinlog.ai.embedding-profile이 비어 있다. FastAPI가 Profile 대조에서 422로 거절하므로 "
			+ "모든 검색이 503이 된다.");
		return "";
	}

	/**
	 * 유사도 내림차순 매칭 목록. Record 단위로 이미 집계돼 있다.
	 *
	 * @param memberId 검색 범위. 인증 경계에서 해석한 값이며 요청 본문이 정하지 않는다
	 * @throws AiSearchException 호출이 실패했을 때. <b>빈 목록으로 대신하지 않는다</b>
	 */
	public List<AiSearchResponse.Match> search(long memberId, String query, int limit) {
		String requestId = currentRequestId();
		AiSearchRequest request = new AiSearchRequest(memberId, query, limit, embeddingProfile);
		try {
			AiSearchResponse response = restClient.post()
				.uri(PATH)
				.header(INTERNAL_SECRET_HEADER, internalSecret)
				.header(REQUEST_ID_HEADER, requestId)
				.body(request)
				.retrieve()
				.body(AiSearchResponse.class);
			return response == null || response.results() == null ? List.of() : response.results();
		} catch (RestClientResponseException e) {
			throw translate(e, requestId);
		} catch (RuntimeException e) {
			log.error("AI search 호출 실패(연결·타임아웃): memberId={}, requestId={}, cause={}",
				memberId, requestId, e.toString());
			throw AiSearchException.unavailable();
		}
	}

	/**
	 * 상태 코드만으로 Profile 불일치를 단정하지 않는다. FastAPI는 <b>요청 검증 실패에도 422를
	 * 쓴다</b>(Pydantic 기본 동작). 상태 코드로만 가르면 운영자가 있지도 않은 설정 불일치를 쫓게
	 * 되므로, 불일치 핸들러만 싣는 {@code serverProfile} 필드가 있는지로 판정한다.
	 */
	private AiSearchException translate(RestClientResponseException failure, String requestId) {
		int status = failure.getStatusCode().value();
		JsonNode body = parse(failure.getResponseBodyAsString());
		if (status == HttpStatus.UNPROCESSABLE_CONTENT.value() && body.has(SERVER_PROFILE_FIELD)) {
			// 양쪽 값을 남기는 것이 이 로그의 목적이다. 어느 쪽을 고쳐야 하는지가 값 비교에서만 나온다.
			log.error("AI search Profile 불일치로 거절됐다(배포 설정이 어긋났다): "
					+ "pinlog.ai.embedding-profile={}, FastAPI={}, requestId={}",
				body.path(REQUEST_PROFILE_FIELD).asString(""),
				body.path(SERVER_PROFILE_FIELD).asString(""),
				requestId);
			return AiSearchException.profileMismatch();
		}
		if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
			log.error("AI search 호출이 {}로 거절됐다(시크릿·헤더 설정 문제): "
					+ "pinlog.ai.internal-secret과 ai 레포의 INTERNAL_SHARED_SECRET이 같은 값인지, "
					+ "헤더 이름이 {}인지 확인하라. requestId={}",
				status, INTERNAL_SECRET_HEADER, requestId);
			return AiSearchException.unavailable();
		}
		// 응답 본문은 남기지 않는다 — 내부 API라도 로그로 새어 나갈 이유가 없다.
		log.error("AI search 호출이 {}를 받았다: requestId={}", status, requestId);
		return AiSearchException.unavailable();
	}

	/** 본문이 비었거나 JSON이 아닐 수 있다(프록시가 끼어든 5xx 등). 그 경우 판정은 "불일치가 아니다"다. */
	private JsonNode parse(String body) {
		if (body.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			return objectMapper.readTree(body);
		} catch (JacksonException e) {
			return objectMapper.createObjectNode();
		}
	}

	/** 요청 스레드에서 동기로 도므로 MDC에 traceId가 있다. 없으면 새로 만든다(로그를 이을 값이 사라진다). */
	private String currentRequestId() {
		String traceId = MDC.get(TraceIdFilter.TRACE_ID);
		return traceId != null ? traceId : UUID.randomUUID().toString();
	}
}
