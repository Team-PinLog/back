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
 * <p>메서드 이름에 대상 범위({@code ForOwner} / {@code Public})를 박는 것도 같은 규약이다
 * (명세 3.1, BD-13). 소유자 응답은 {@code PUBLIC + PRIVATE_ONLY}, 타인 응답은 {@code PUBLIC}만이다
 * (04 §2 Visibility 표) — 범위가 메서드 단위로 갈리므로 호출부가 조건을 고를 여지가 없다.
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

	/**
	 * 타인 응답용 Record 단위 집계. {@code ForOwner}와 두 가지가 다르다 — visibility가
	 * {@code PUBLIC} <b>화이트리스트 하나</b>이고, {@code member_id} 방어 조건이 없다(타인 조회라
	 * 요청자와 소유자가 다른 것이 정상이며, 공개 여부 판정은 호출부의 발행 Collection 경로가 이미
	 * 끝냈다).
	 */
	private static final String KEYWORDS_PUBLIC_SQL = """
		SELECT DISTINCT ct.record_id AS record_id, kp.display_name AS display_name
		FROM core.context ct
		JOIN ai.context_ai_state st ON st.context_id = ct.id
		JOIN ai.context_keyword  ck ON ck.context_id = ct.id
		JOIN ai.keyword_preset   kp ON kp.id = ck.keyword_id
		WHERE ct.record_id IN (:recordIds)
			AND ct.deleted_at IS NULL
			AND st.keyword_status = 'COMPLETED'
			AND kp.visibility = 'PUBLIC'
			AND kp.is_active = true
		ORDER BY ct.record_id, kp.display_name
		""";

	/**
	 * Collection 단위 집계(타인 응답용, API 명세 8.1·9.3). Collection Keyword는 물리 컬럼이 아니라
	 * 담긴 Record들의 Context Keyword를 모은 파생값이다(BD-18) — {@code collection_record}를
	 * 거슬러 올라가 모은다.
	 *
	 * <p>{@code r.deleted_at} 조건은 방어다. Record 삭제가 Context·링크를 연쇄 소프트 삭제하므로
	 * 보통은 {@code ct.deleted_at}·{@code cr.deleted_at}에 이미 걸리지만, 연쇄가 한 곳이라도
	 * 어긋난 데이터에서 삭제된 Record의 Keyword가 살아나면 안 된다.
	 *
	 * <p>Feed의 {@code FeedKeywordRepository}와 겹쳐 보이지만 합치지 않는다 — 그쪽은 점수 계산용
	 * {@code code}·가중치 분포(AI 파트 소유 계약)이고, 여기는 화면 표시용 {@code display_name}
	 * 목록이다. 반환 계약이 달라 한쪽을 바꾸면 다른 쪽이 조용히 깨진다.
	 */
	private static final String COLLECTION_KEYWORDS_PUBLIC_SQL = """
		SELECT DISTINCT cr.collection_id AS collection_id, kp.display_name AS display_name
		FROM core.collection_record cr
		JOIN core.record  r  ON r.id = cr.record_id AND r.deleted_at IS NULL
		JOIN core.context ct ON ct.record_id = cr.record_id AND ct.deleted_at IS NULL
		JOIN ai.context_ai_state st ON st.context_id = ct.id
		JOIN ai.context_keyword  ck ON ck.context_id = ct.id
		JOIN ai.keyword_preset   kp ON kp.id = ck.keyword_id
		WHERE cr.collection_id IN (:collectionIds)
			AND cr.deleted_at IS NULL
			AND st.keyword_status = 'COMPLETED'
			AND kp.visibility = 'PUBLIC'
			AND kp.is_active = true
		ORDER BY cr.collection_id, kp.display_name
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

	/**
	 * 타인 응답용 Record 단위 Keyword. 한 페이지의 Record 전부를 <b>한 번의 쿼리로</b> 모은다.
	 *
	 * @return Record id → {@code PUBLIC} Keyword {@code display_name} 목록. Keyword가 없는 Record는
	 *     키가 없다 — 호출부가 빈 목록으로 채운다
	 */
	public Map<Long, List<String>> findKeywordsPublic(List<Long> recordIds) {
		if (recordIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<String>> byRecord = new LinkedHashMap<>();
		jdbc.query(KEYWORDS_PUBLIC_SQL, Map.of("recordIds", recordIds), rows -> {
			byRecord.computeIfAbsent(rows.getLong("record_id"), key -> new ArrayList<>())
				.add(rows.getString("display_name"));
		});
		return byRecord;
	}

	/**
	 * 타인 응답용 Collection 단위 Keyword. 한 페이지의 Collection 전부를 <b>한 번의 쿼리로</b>
	 * 모은다 — 항목마다 반복 조회하면 그대로 N+1이다(BD-18).
	 *
	 * @return Collection id → {@code PUBLIC} Keyword {@code display_name} 목록. Keyword가 없는
	 *     Collection은 키가 없다 — 호출부가 빈 목록으로 채운다. AI 미완료와 "Keyword 0건"은
	 *     응답에서 구분하지 않는다
	 */
	public Map<Long, List<String>> findCollectionKeywordsPublic(List<Long> collectionIds) {
		if (collectionIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<String>> byCollection = new LinkedHashMap<>();
		jdbc.query(COLLECTION_KEYWORDS_PUBLIC_SQL, Map.of("collectionIds", collectionIds), rows -> {
			byCollection.computeIfAbsent(rows.getLong("collection_id"), key -> new ArrayList<>())
				.add(rows.getString("display_name"));
		});
		return byCollection;
	}
}
