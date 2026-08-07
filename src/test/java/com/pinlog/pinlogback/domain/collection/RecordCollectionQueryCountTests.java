package com.pinlog.pinlogback.domain.collection;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import com.pinlog.pinlogback.support.CoreApiFixtures;
import com.pinlog.pinlogback.support.SqlQueryCounter;

/**
 * Record가 담긴 Collection 목록(명세 5.10)이 <b>항목 수와 무관하게</b> Collection 페이지와 Keyword
 * 집계를 한 번씩만 조회한다 — 측정으로 확인한다.
 *
 * <p>이 목록의 길이에는 상한이 없다. 한 Record가 담길 수 있는 Collection 수에 제한이 없으므로,
 * Collection마다 Keyword를 부르도록 짜면 컬렉션을 잘게 쓰는 사용자에게서만 드러난다. 그래서
 * "일괄로 짰다"를 코드 읽기가 아니라 항목이 늘어도 쿼리 수가 그대로다로 붙잡아 둔다.
 *
 * <p>{@code @Import}가 Spring 컨텍스트 캐시 키를 바꿔 컨텍스트를 하나 더 띄우므로 계약 단언을
 * 여기에 얹지 않는다 — 계약은 {@link RecordCollectionApiTests}에 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SqlQueryCounter.Config.class)
class RecordCollectionQueryCountTests extends CoreApiFixtures {

	/** Collection 페이지. */
	private static final String COLLECTION_QUERY = "from core.collection";

	/** 소유 확인. */
	private static final String RECORD_QUERY = "from core.record";

	/** Keyword 집계. {@code ai.context_keyword}를 타는 것은 이 쿼리뿐이다. */
	private static final String KEYWORD_QUERY = "JOIN ai.context_keyword  ck";

	@Autowired
	private SqlQueryCounter queries;

	@Test
	void keywordLookupStaysOneQueryAsThePageGrows() throws Exception {
		long me = newMemberId();
		long small = createRecord(me, seed("rcq-small"), "적은 쪽");
		for (int i = 0; i < 2; i++) {
			createCollection(me, "책 " + i, List.of(small));
		}

		queries.reset();
		fetch(me, small);
		long keywordBaseline = queries.count(KEYWORD_QUERY);

		assertThat(keywordBaseline)
			.as("Keyword는 페이지의 collectionId를 모아 한 번에 조회한다. 실제 SQL: %s",
				queries.matching(KEYWORD_QUERY))
			.isEqualTo(1);

		long large = createRecord(me, seed("rcq-large"), "많은 쪽");
		for (int i = 0; i < 8; i++) {
			createCollection(me, "많은 책 " + i, List.of(large));
		}

		queries.reset();
		fetch(me, large);

		assertThat(queries.count(KEYWORD_QUERY)).isEqualTo(keywordBaseline);
		assertThat(queries.count(COLLECTION_QUERY))
			.as("Collection 페이지는 한 번에 읽는다. 실제 SQL: %s", queries.matching(COLLECTION_QUERY))
			.isEqualTo(1);
		assertThat(queries.count(RECORD_QUERY))
			.as("소유 확인은 한 번이다")
			.isEqualTo(1);
	}

	private void fetch(long memberId, long recordId) throws Exception {
		mockMvc.perform(get("/v1/records/{recordId}/collections", recordId)
				.param("size", "20").with(loginAs(memberId)))
			.andExpect(status().isOk());
	}

	private String seed(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
	}
}
