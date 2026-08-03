package com.pinlog.pinlogback.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import com.pinlog.pinlogback.support.SqlQueryCounter;

/**
 * 표시값 매핑이 N+1을 만들지 않는다 — <b>측정으로</b> 확인한다(S15P11A705-252 완료 조건).
 *
 * <p>이 검증이 필요한 이유는 프리셋이 27개뿐이라는 데 있다. 항목마다 조회하도록 짜도 개발·테스트
 * 데이터에서는 응답이 멀쩡하고 지연도 눈에 띄지 않는다. 그래서 "일괄로 짰다"는 코드 읽기가 아니라
 * <b>후보를 늘려도 쿼리 수가 그대로다</b>를 실행으로 붙잡아 둔다.
 *
 * <p>{@code feed-tests.md} 9장이 N4~N7을 "쿼리 카운터로 검증한다"고 적어 두고도 카운터가 없어
 * 검증되지 않은 상태였다. 여기서 N4·N5도 함께 센다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SqlQueryCounter.Config.class)
class FeedKeywordQueryCountTests extends FeedFixtures {

	/** 표시값 조회. 집계 쪽은 {@code JOIN ai.keyword_preset}이라 이 조각에 걸리지 않는다. */
	private static final String DISPLAY_NAME_QUERY = "FROM ai.keyword_preset";

	/** Collection 특징 집계(N4·N5). */
	private static final String COLLECTION_FEATURE_QUERY = "FROM core.collection_record cr";

	@Autowired
	private SqlQueryCounter queries;

	@Test
	void theDisplayNameLookupStaysOneQueryAsCandidatesAndKeywordsGrow() throws Exception {
		long viewer = newMemberId();
		givenCollectionsWithDistinctKeywords(3, "small");

		queries.reset();
		feedPayload(viewer);
		long baseline = queries.count(DISPLAY_NAME_QUERY);

		assertThat(baseline)
			.as("표시값은 페이지 전체의 code를 모아 한 번에 조회한다. 실제 SQL: %s",
				queries.matching(DISPLAY_NAME_QUERY))
			.isEqualTo(1);

		givenCollectionsWithDistinctKeywords(12, "grown");

		queries.reset();
		feedPayload(viewer);

		assertThat(queries.count(DISPLAY_NAME_QUERY))
			.as("후보와 Keyword가 늘어도 표시값 조회 횟수는 그대로여야 한다 — 늘면 N+1이다")
			.isEqualTo(baseline);
	}

	/** N4·N5 — 후보 전체의 특징을 {@code IN (...)} 한 번으로 집계한다. */
	@Test
	void collectionFeaturesAreAggregatedInASingleQuery() throws Exception {
		long viewer = newMemberId();
		givenCollectionsWithDistinctKeywords(8, "feature");

		queries.reset();
		feedPayload(viewer);

		assertThat(queries.count(COLLECTION_FEATURE_QUERY))
			.as("Collection별 반복 조회는 후보 200건에 그대로 N+1이다. 실제 SQL: %s",
				queries.matching(COLLECTION_FEATURE_QUERY))
			.isEqualTo(1);
	}

	/**
	 * 소유자를 나누는 이유는 다양성 조정이 소유자당 2건으로 자르기 때문이다 — 한 소유자로 몰면
	 * 응답에 실리는 항목이 줄어 "페이지가 커져도 그대로"를 볼 수 없다.
	 */
	private void givenCollectionsWithDistinctKeywords(int count, String tag) throws Exception {
		for (int index = 0; index < count; index++) {
			long owner = newMemberId();
			long recordId = createRecord(owner, uniqueSeed("qc-" + tag + index));
			attachKeyword(recordId, insertPreset(
				uniqueCode("QC"), uniqueDisplayName("측정"), "PUBLIC", true));
			createCollection(owner, "측정 책 " + tag + index, List.of(recordId));
		}
	}
}
