package com.pinlog.pinlogback.domain.follow;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.support.CoreApiFixtures;
import com.pinlog.pinlogback.support.SqlQueryCounter;

/**
 * 팔로우 수와 무관하게 Collection 집계 질의가 1회다 — <b>측정으로</b> 확인한다(S15P11A705-244
 * 완료 조건). 이 작업의 존재 이유가 호출·질의 수 절감이라, 책장별 반복 조회로 되돌아가는 회귀를
 * 코드 읽기가 아니라 쿼리 카운터로 막는다({@code FeedKeywordQueryCountTests}와 같은 방식).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SqlQueryCounter.Config.class)
class FollowListCollectionsQueryCountTests extends CoreApiFixtures {

	/** 책장별 첫 페이지를 창 함수로 모으는 집계 질의. 이 조각을 쓰는 SQL은 그것뿐이다. */
	private static final String AGGREGATE_QUERY = "PARTITION BY";

	/** Collection 단위 Keyword 집계({@code ContextKeywordRepository}) — 이쪽도 페이지당 1회여야 한다. */
	private static final String KEYWORD_QUERY = "FROM core.collection_record cr";

	@Autowired
	private CollectionRepository collectionRepository;

	@Autowired
	private SqlQueryCounter queries;

	@Test
	void collectionAggregationStaysOneQueryAsFollowsGrow() throws Exception {
		long me = newMemberId();
		followNewShelves(me, 2);

		queries.reset();
		listWithCollections(me);
		long baseline = queries.count(AGGREGATE_QUERY);
		assertThat(baseline)
			.as("책장별 첫 페이지는 창 함수 한 번으로 모은다. 실제 SQL: %s", queries.matching(AGGREGATE_QUERY))
			.isEqualTo(1);
		assertThat(queries.count(KEYWORD_QUERY)).isEqualTo(1);

		followNewShelves(me, 3);

		queries.reset();
		listWithCollections(me);
		assertThat(queries.count(AGGREGATE_QUERY))
			.as("팔로우가 늘어도 집계 질의 수는 그대로여야 한다 — 늘면 책장 수만큼의 N+1이다")
			.isEqualTo(baseline);
		assertThat(queries.count(KEYWORD_QUERY)).isEqualTo(1);
	}

	private void followNewShelves(long memberId, int count) throws Exception {
		for (int index = 0; index < count; index++) {
			long owner = newMemberId();
			long collectionId = collectionRepository.save(Collection.create(owner, "측정 책장 " + index)).getId();
			collectionRepository.save(Collection.create(owner, "측정 둘째 " + index));
			follow(memberId, collectionId);
		}
	}

	private void listWithCollections(long memberId) throws Exception {
		mockMvc.perform(get("/v1/follows").with(loginAs(memberId)).param("collectionSize", "1"))
			.andExpect(status().isOk());
	}
}
