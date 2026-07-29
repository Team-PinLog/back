package com.pinlog.pinlogback.domain.ai;

import static com.pinlog.pinlogback.domain.ai.repository.AiDerivedDataRepository.CANCELLED;
import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.ai.repository.AiDerivedDataRepository;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Context가 소프트 삭제되는 모든 경로에서 {@code ai} 스키마 파생 데이터가 함께 무효화되는지
 * 고정한다(데이터모델 6.4~6.6, API 명세 3.4 "AI 파생 데이터").
 *
 * <p>증상이 지금 보이지 않는 것이 이 테스트가 필요한 이유다. 검색 제외는
 * {@code context_embedding.is_deleted = false} 필터가 단독으로 담당하므로, 플래그가 꺼진 채
 * 자연어 검색이 붙으면 <b>삭제한 Context가 검색 결과에 계속 나온다.</b>
 *
 * <p>AI 워커의 State·Embedding 생성은 아직 붙지 않았으므로 여기서는 파생 데이터를 직접 넣어
 * 워커가 만들어 둔 상태를 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiDerivedDataInvalidationTests extends IntegrationContainerSupport {

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * 롤백 테스트에서만 던지도록 스텁해 삭제 트랜잭션을 실패시킨다. 다른 테스트에서는 스텁하지
	 * 않으므로 실제 빈에 그대로 위임한다.
	 */
	@MockitoSpyBean
	private CollectionRepository collectionRepository;

	/**
	 * 롤백 테스트에서 "무효화가 실제로 호출됐는가"만 확인한다. 스텁하지 않으므로 어느 테스트에서도
	 * 동작이 바뀌지 않는다.
	 */
	@MockitoSpyBean
	private AiDerivedDataRepository aiDerivedDataRepository;

	/** 적용 지점 1 — Context 삭제(6.5). */
	@Test
	void contextDeleteCancelsStateAndMarksEmbeddingDeleted() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-ctx-1", "첫 번째");
		long contextId = addContext(memberId, recordId, "두 번째");
		givenDerivedData(memberId, recordId, contextId, "COMPLETED", "COMPLETED");

		mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(state(contextId)).containsEntry("embedding_status", CANCELLED)
			.containsEntry("keyword_status", CANCELLED);
		assertThat(embeddingDeleted(contextId)).isTrue();
	}

	/** 적용 지점 2 — Context 수정의 구 Context(6.4). 새 Context는 건드리지 않는다. */
	@Test
	void contextReplaceInvalidatesOnlyTheOldContext() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-replace-1", "옛 맥락");
		long oldContextId = firstContextId(memberId, recordId);
		long survivingContextId = addContext(memberId, recordId, "남을 맥락");
		givenDerivedData(memberId, recordId, oldContextId, "COMPLETED", "COMPLETED");
		givenDerivedData(memberId, recordId, survivingContextId, "COMPLETED", "COMPLETED");

		JsonNode replaced = parse(mockMvc.perform(
				patch("/v1/records/{recordId}/contexts/{contextId}", recordId, oldContextId)
					.with(loginAs(memberId))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\": \"새 맥락\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		long newContextId = replaced.at("/data/contextId").asLong();

		assertThat(newContextId).isNotEqualTo(oldContextId);
		assertThat(state(oldContextId)).containsEntry("embedding_status", CANCELLED)
			.containsEntry("keyword_status", CANCELLED);
		assertThat(embeddingDeleted(oldContextId)).isTrue();

		// 같은 Record의 살아남은 Context는 그대로다 — 무효화 대상은 지워진 Context뿐이다.
		assertThat(state(survivingContextId)).containsEntry("embedding_status", "COMPLETED")
			.containsEntry("keyword_status", "COMPLETED");
		assertThat(embeddingDeleted(survivingContextId)).isFalse();
	}

	/** 적용 지점 3 — Record 삭제(6.6). 활성 Context 전체가 대상이다. */
	@Test
	void recordDeleteInvalidatesEveryActiveContextOfThatRecord() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-rec-1", "첫 번째");
		long otherRecordId = createRecord(memberId, "ai-inv-rec-2", "다른 기록");
		long firstContextId = firstContextId(memberId, recordId);
		long secondContextId = addContext(memberId, recordId, "두 번째");
		long untouchedContextId = firstContextId(memberId, otherRecordId);
		createCollection(memberId, "살아남을 책", List.of(recordId, otherRecordId));
		givenDerivedData(memberId, recordId, firstContextId, "COMPLETED", "PENDING");
		givenDerivedData(memberId, recordId, secondContextId, "PROCESSING", "COMPLETED");
		givenDerivedData(memberId, otherRecordId, untouchedContextId, "COMPLETED", "COMPLETED");

		mockMvc.perform(delete("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(state(firstContextId)).containsEntry("embedding_status", CANCELLED)
			.containsEntry("keyword_status", CANCELLED);
		assertThat(state(secondContextId)).containsEntry("embedding_status", CANCELLED)
			.containsEntry("keyword_status", CANCELLED);
		assertThat(embeddingDeleted(firstContextId)).isTrue();
		assertThat(embeddingDeleted(secondContextId)).isTrue();

		assertThat(state(untouchedContextId)).containsEntry("embedding_status", "COMPLETED");
		assertThat(embeddingDeleted(untouchedContextId)).isFalse();
	}

	/** 적용 지점 3 — 강제 삭제도 같은 결과다(6.6 "연쇄 대상이 없어도 정상 수행"). */
	@Test
	void forceRecordDeleteInvalidatesEveryActiveContextOfThatRecord() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-force-1", "유일한 맥락");
		long contextId = firstContextId(memberId, recordId);
		createCollection(memberId, "함께 사라질 책", List.of(recordId));
		givenDerivedData(memberId, recordId, contextId, "COMPLETED", "COMPLETED");

		mockMvc.perform(delete("/v1/records/{recordId}/force", recordId).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(state(contextId)).containsEntry("embedding_status", CANCELLED)
			.containsEntry("keyword_status", CANCELLED);
		assertThat(embeddingDeleted(contextId)).isTrue();
	}

	/**
	 * {@code CANCELLED} 전이에 조건을 걸지 않는다. {@code ai.context_keyword}에는 {@code is_deleted}에
	 * 해당하는 컬럼이 없어 키워드 조회 제외를 {@code keyword_status}가 단독으로 담당한다 —
	 * {@code COMPLETED}·{@code FAILED}를 남기면 지운 Context의 Keyword가 계속 노출된다.
	 */
	@Test
	void terminalStatusesAreOverwrittenByCancelled() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-terminal-1", "첫 번째");
		long completedContextId = addContext(memberId, recordId, "COMPLETED 쪽");
		long failedContextId = addContext(memberId, recordId, "FAILED 쪽");
		givenDerivedData(memberId, recordId, completedContextId, "COMPLETED", "COMPLETED");
		givenDerivedData(memberId, recordId, failedContextId, "FAILED", "FAILED");

		for (long contextId : List.of(completedContextId, failedContextId)) {
			mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
					.with(loginAs(memberId)))
				.andExpect(status().isNoContent());
			assertThat(state(contextId)).containsEntry("embedding_status", CANCELLED)
				.containsEntry("keyword_status", CANCELLED);
		}
	}

	/**
	 * Embedding 생성 전에 삭제된 경우다. 임베딩·Keyword는 비동기 생성이라 커밋 직후에는 아직
	 * 파생 데이터가 없다 — 영향 행 0은 오류가 아니다.
	 */
	@Test
	void deletingAContextWithoutDerivedDataSucceeds() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-none-1", "첫 번째");
		long noEmbeddingContextId = addContext(memberId, recordId, "State만 있음");
		long bareContextId = addContext(memberId, recordId, "State도 Embedding도 없음");
		insertState(noEmbeddingContextId, "PENDING", "PENDING");

		mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, noEmbeddingContextId)
				.with(loginAs(memberId)))
			.andExpect(status().isNoContent());
		mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, bareContextId)
				.with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(state(noEmbeddingContextId)).containsEntry("embedding_status", CANCELLED)
			.containsEntry("keyword_status", CANCELLED);
		assertThat(embeddingRowCount(noEmbeddingContextId)).isZero();
		assertThat(embeddingRowCount(bareContextId)).isZero();
	}

	/** 파생 데이터가 하나도 없는 Record를 통째로 지워도 실패하지 않는다. */
	@Test
	void forceDeletingARecordWithoutDerivedDataSucceeds() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-none-rec-1", "유일한 맥락");
		long contextId = firstContextId(memberId, recordId);

		mockMvc.perform(delete("/v1/records/{recordId}/force", recordId).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(stateRowCount(contextId)).isZero();
		assertThat(embeddingRowCount(contextId)).isZero();
	}

	/**
	 * 409로 거절된 삭제는 파생 데이터도 건드리지 않는다.
	 *
	 * <p><b>이 테스트가 고정하는 것은 제어 흐름이다</b> — 두 경로 모두 409를 무효화 호출 <b>앞</b>에서
	 * 던지므로 {@code invalidate}가 애초에 실행되지 않는다는 사실. 롤백과는 무관하며, 무효화를
	 * 트랜잭션 밖으로 옮겨도 이 테스트는 통과한다. 원자성은
	 * {@link #invalidationRollsBackWhenTheDeletionTransactionFails}가 따로 고정한다.
	 *
	 * <p>거절 경로에 무효화가 새어 들어오면(예: 개수 검사보다 앞에서 부르면) 거절된 요청이 검색에서만
	 * Context를 지우는 상태가 만들어진다. 그것을 막는 것이 이 테스트의 몫이다.
	 */
	@Test
	void rejectedDeleteLeavesDerivedDataUntouched() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-reject-1", "유일한 맥락");
		long contextId = firstContextId(memberId, recordId);
		long collectionId = createCollection(memberId, "마지막인 책", List.of(recordId));
		givenDerivedData(memberId, recordId, contextId, "COMPLETED", "COMPLETED");

		mockMvc.perform(delete("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(memberId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DELETE_CONFIRMATION_REQUIRED"));
		mockMvc.perform(delete("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.impact.collectionIds[0]").value(collectionId));

		assertThat(state(contextId)).containsEntry("embedding_status", "COMPLETED")
			.containsEntry("keyword_status", "COMPLETED");
		assertThat(embeddingDeleted(contextId)).isFalse();
	}

	/**
	 * <b>무효화 UPDATE가 삭제 트랜잭션과 함께 되돌아간다</b>(BD-37이 (b)·(c)를 기각하며 택한 원자성).
	 *
	 * <p>{@code cascadeDelete}는 {@code invalidate}를 Collection 루프 <b>앞</b>에서 부른다. 그래서
	 * {@code findByIdForUpdate}가 던지게 만들면 무효화 UPDATE 두 개가 이미 나간 뒤 트랜잭션이 실패하는
	 * 상황이 만들어진다. 실패 지점을 {@code AiDerivedDataRepository} <b>바깥</b>에 두는 것이 중요하다 —
	 * 스파이의 {@code callRealMethod}로 안쪽에서 던지면 트랜잭션 프록시를 우회해 {@code REQUIRES_NEW}
	 * 분리를 놓친다(실측).
	 *
	 * <p>단언 둘이 짝이다. 하나만으로는 증명이 되지 않는다:
	 *
	 * <ul>
	 *   <li>{@code verify(invalidate)} — 제어 흐름이 무효화까지 갔다. 없으면 "예외 때문에 애초에
	 *       호출되지 않았다"와 구분되지 않는다.</li>
	 *   <li>파생 데이터가 {@code COMPLETED}·{@code is_deleted = false} 그대로 — 그 UPDATE가 core 삭제와
	 *       함께 되돌아갔다. 없으면 롤백을 보지 못한다.</li>
	 * </ul>
	 *
	 * <p>{@code REQUIRES_NEW}로 떼면 안쪽이 먼저 커밋돼 두 번째가, 커밋 후 별도 호출로 떼면 첫 번째가
	 * 깨진다(둘 다 실측). <b>대신 이 테스트는 "무효화가 Collection 루프보다 앞"이라는 순서에 의존한다</b>
	 * — 무효화를 루프 뒤로 옮기면 트랜잭션 안에 있어도 {@code verify}가 깨지므로, 그때는 실패 주입
	 * 지점도 함께 뒤로 옮겨야 한다.
	 */
	@Test
	void invalidationRollsBackWhenTheDeletionTransactionFails() throws Exception {
		long memberId = newMemberId();
		long recordId = createRecord(memberId, "ai-inv-rollback-1", "유일한 맥락");
		long contextId = firstContextId(memberId, recordId);
		createCollection(memberId, "함께 되돌아갈 책", List.of(recordId));
		givenDerivedData(memberId, recordId, contextId, "COMPLETED", "COMPLETED");

		doThrow(new IllegalStateException("Collection 잠금 획득 실패를 가장한다"))
			.when(collectionRepository).findByIdForUpdate(anyLong());

		mockMvc.perform(delete("/v1/records/{recordId}/force", recordId).with(loginAs(memberId)))
			.andExpect(status().isInternalServerError());

		verify(aiDerivedDataRepository).invalidate(List.of(contextId));
		assertThat(state(contextId)).containsEntry("embedding_status", "COMPLETED")
			.containsEntry("keyword_status", "COMPLETED");
		assertThat(embeddingDeleted(contextId)).isFalse();
		// core 쪽도 함께 되돌아갔다 — 한쪽만 남는 부분 실패가 이 결정이 막으려던 것이다.
		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(memberId)))
			.andExpect(status().isOk());
	}

	private void givenDerivedData(long memberId, long recordId, long contextId,
		String embeddingStatus, String keywordStatus) {
		insertState(contextId, embeddingStatus, keywordStatus);
		jdbcTemplate.update("""
			INSERT INTO ai.context_embedding
				(context_id, user_id, record_id, embedding, embedding_profile)
			VALUES (?, ?, ?, ?::vector, 'test-profile')
			""", contextId, memberId, recordId, zeroVector());
	}

	private void insertState(long contextId, String embeddingStatus, String keywordStatus) {
		jdbcTemplate.update("""
			INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status)
			VALUES (?, ?, ?)
			""", contextId, embeddingStatus, keywordStatus);
	}

	/** {@code VECTOR(1536)}은 NOT NULL이라 값이 필요하다. 유사도를 보지 않으므로 영벡터로 채운다. */
	private static String zeroVector() {
		StringJoiner joiner = new StringJoiner(",", "[", "]");
		for (int dimension = 0; dimension < 1536; dimension++) {
			joiner.add("0");
		}
		return joiner.toString();
	}

	private Map<String, Object> state(long contextId) {
		return jdbcTemplate.queryForMap(
			"SELECT embedding_status, keyword_status FROM ai.context_ai_state WHERE context_id = ?",
			contextId);
	}

	private long stateRowCount(long contextId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM ai.context_ai_state WHERE context_id = ?", Long.class, contextId);
	}

	private boolean embeddingDeleted(long contextId) {
		return jdbcTemplate.queryForObject(
			"SELECT is_deleted FROM ai.context_embedding WHERE context_id = ?", Boolean.class, contextId);
	}

	private long embeddingRowCount(long contextId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM ai.context_embedding WHERE context_id = ?", Long.class, contextId);
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long createRecord(long memberId, String kakaoPlaceId, String contextBody) throws Exception {
		String body = """
			{
			\t"place": {
			\t\t"kakaoPlaceId": "%s",
			\t\t"name": "장소",
			\t\t"address": "주소",
			\t\t"lat": 37.5,
			\t\t"lng": 127.0
			\t},
			\t"contextBody": "%s"
			}
			""".formatted(kakaoPlaceId, contextBody);
		JsonNode response = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/recordId").asLong();
	}

	private long firstContextId(long memberId, long recordId) throws Exception {
		JsonNode detail = parse(mockMvc.perform(get("/v1/records/{recordId}", recordId)
				.with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		return detail.at("/data/contexts/0/contextId").asLong();
	}

	private long addContext(long memberId, long recordId, String body) throws Exception {
		JsonNode response = parse(mockMvc.perform(
				post("/v1/records/{recordId}/contexts", recordId).with(loginAs(memberId))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"body\": \"" + body + "\"}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/contextId").asLong();
	}

	private long createCollection(long memberId, String title, List<Long> recordIds) throws Exception {
		String ids = recordIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		JsonNode response = parse(mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"" + title + "\", \"recordIds\": [" + ids + "]}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/collectionId").asLong();
	}

	private JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
