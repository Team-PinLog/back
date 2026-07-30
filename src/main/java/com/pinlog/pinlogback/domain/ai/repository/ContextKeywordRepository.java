package com.pinlog.pinlogback.domain.ai.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Record 단위 Keyword 집계(AI 파트 소유 명세 {@code docs/ai/spec/ai-response-assembly.md} 4.1).
 * {@code ai} 스키마를 <b>읽기 조인만</b> 한다 — 요청 경로에서 FastAPI를 호출하지 않는다.
 *
 * <p><b>가시성 필터를 WHERE 절에 두는 것이 이 클래스의 존재 이유다.</b> 자바 코드에서 거르면 새
 * 호출 경로가 생길 때 조용히 누락되고, 그 누락이 곧 개인정보 노출이다(BD-13).
 * {@code BLOCKED}는 화이트리스트 밖이라 어느 메서드에도 등장하지 않는다 — 블랙리스트로 쓰면
 * 나중에 추가되는 visibility 값이 그냥 통과한다.
 *
 * <p>메서드 이름에 대상 범위({@code ForOwner})를 박는 것도 같은 규약이다(명세 3.1). 공개용
 * ({@code visibility = 'PUBLIC'}) 경로는 이 티켓에 소비자가 없어 만들지 않는다 — 검색은 본인
 * 데이터 전용이다(AI 설계 9.1).
 */
@Repository
public class ContextKeywordRepository {

	/**
	 * 세 조건이 각각 담당하는 것(명세 4.1).
	 *
	 * <ul>
	 *   <li>{@code st.keyword_status = 'COMPLETED'} — 미완료·실패·취소 제외. 수정으로 교체된 구
	 *       Context는 {@code deleted_at}과 이 조건에 <b>이중으로</b> 걸려 나오지 않는다(명세 4.2)</li>
	 *   <li>{@code kp.is_active = true} — 폐기된 Preset은 행 삭제가 아니라 이 플래그로 처리된다</li>
	 *   <li>{@code kp.visibility IN (...)} — 소유자 응답의 공개 범위</li>
	 * </ul>
	 *
	 * <p>{@code ct.member_id}까지 보는 것은 방어다. 호출부가 이미 소유권을 검증한 Record만 넘기지만,
	 * 이 조건이 있으면 <b>넘기는 쪽이 실수해도</b> 남의 Keyword가 섞이지 않는다.
	 *
	 * <p>{@code DISTINCT}는 같은 Keyword가 여러 Context에 붙은 경우를 위한 것이다. Record 단위
	 * 집계값이므로 중복은 제거하고, {@code ORDER BY}로 응답 순서를 고정한다 — 순서가 흔들리면
	 * 테스트가 불안정해지고 클라이언트 캐시도 무의미해진다.
	 */
	private static final String KEYWORDS_FOR_OWNER_SQL = """
		SELECT DISTINCT ct.record_id AS record_id, kp.display_name AS display_name
		FROM core.context ct
		JOIN ai.context_ai_state st ON st.context_id = ct.id
		JOIN ai.context_keyword  ck ON ck.context_id = ct.id
		JOIN ai.keyword_preset   kp ON kp.id = ck.keyword_id
		WHERE ct.record_id IN (:recordIds)
			AND ct.member_id = :memberId
			AND ct.deleted_at IS NULL
			AND st.keyword_status = 'COMPLETED'
			AND kp.visibility IN ('PUBLIC', 'PRIVATE_ONLY')
			AND kp.is_active = true
		ORDER BY ct.record_id, kp.display_name
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public ContextKeywordRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 넘긴 Record들의 Keyword를 <b>한 번의 쿼리로</b> 모은다(명세 4.3 — Record별 반복 조회는 그대로
	 * N+1이다).
	 *
	 * <p>매칭된 Context가 아니라 <b>Record의 활성 Context 전체</b>를 집계한다(API 명세 6.1). 매칭
	 * Context만 보면 같은 Record의 다른 Context가 가진 Keyword가 사라진다.
	 *
	 * @return Record id → Keyword {@code display_name} 목록. <b>Keyword가 없는 Record는 키가
	 *     없다</b> — 호출부가 빈 목록으로 채운다. AI 미완료와 "매칭 0건"은 응답에서 구분하지
	 *     않는다(명세 5장)
	 */
	public Map<Long, List<String>> findKeywordsForOwner(List<Long> recordIds, long memberId) {
		if (recordIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<String>> byRecord = new LinkedHashMap<>();
		jdbc.query(KEYWORDS_FOR_OWNER_SQL, Map.of("recordIds", recordIds, "memberId", memberId), rows -> {
			byRecord.computeIfAbsent(rows.getLong("record_id"), key -> new ArrayList<>())
				.add(rows.getString("display_name"));
		});
		return byRecord;
	}
}
