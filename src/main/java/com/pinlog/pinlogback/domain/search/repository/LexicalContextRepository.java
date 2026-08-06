package com.pinlog.pinlogback.domain.search.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 본문에 질의 문자열이 그대로 있는 Record를 찾는다(P49 §3의 문자열 검색, 규칙의 실측 근거는
 * ai 레포 I54).
 *
 * <p>범위를 {@code core.context.member_id}로 좁힌다 — 이 비정규화 컬럼의 존재 이유가 바로
 * 「자연어 검색은 항상 본인 맥락 한정」이다({@code Context} 엔티티 주석). 이 필터는 검색 범위이지
 * 인가가 아니다. 인가는 벡터 후보와 똑같이 {@code SearchRecordRepository.findVerified}의 Core
 * 재검증이 맡고, 문자열 후보는 그 재검증을 우회하지 않는다.
 *
 * <p>매치는 부분일치다. 어절 시작 경계 요구는 실측에서 기대 정답 6건을 잃는 손해만 관측되어
 * 채택되지 않았다(I54). 남는 오탐 유형(어절 중간 시작 일치·동형어)의 재평가는 운영 코퍼스
 * 조건으로 미뤄져 있다(P49 §9).
 */
@Repository
public class LexicalContextRepository {

	/**
	 * @param recordId 매치된 Record
	 * @param contextId 그 Record의 대표 매치 Context — {@code matchedContext}의 근거다
	 */
	public record LexicalMatch(long recordId, long contextId) {
	}

	/**
	 * 안쪽 {@code DISTINCT ON}이 Record당 대표 Context 하나를 고른다 — 교체 생성(BD-07)으로 구본과
	 * 신본이 함께 매치되면 최신 것({@code created_at} 내림차순, 동시각이면 {@code id} 내림차순)이다.
	 * FastAPI가 Record별 최고 유사도 Context를 대표로 고르는 것(AI 설계 9.4)의 문자열 쪽 등가물이다.
	 *
	 * <p>바깥 정렬이 곧 문자열 목록의 순위다 — 최초 작성 시각({@code origin_created_at}, 목록
	 * 정렬·날짜 표시의 기준 컬럼) 내림차순. 실측(I54)은 코사인 내림차순을 썼지만 그 값은 FastAPI
	 * 밖으로 나오지 않아 여기서는 쓸 수 없고, 대신 이 도메인의 기존 정렬 기준을 따른다. 어떤
	 * 기준이든 <b>결정적</b>이어야 같은 질의가 같은 순서를 돌려준다.
	 */
	private static final String MATCH_SQL = """
		SELECT record_id, context_id FROM (
			SELECT DISTINCT ON (ct.record_id)
				ct.record_id AS record_id, ct.id AS context_id, ct.origin_created_at AS matched_at
			FROM core.context ct
			WHERE ct.member_id = :memberId
				AND ct.deleted_at IS NULL
				AND ct.body LIKE :pattern ESCAPE '\\'
			ORDER BY ct.record_id, ct.created_at DESC, ct.id DESC
		) matched
		ORDER BY matched_at DESC, record_id DESC
		LIMIT :limit
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public LexicalContextRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param query 앞뒤 공백을 정리한 단어형 질의. 단어형 판정은 호출부의 게이트가 이미 마쳤다
	 * @param limit 문자열 목록 순위 상위 몇 건까지 후보로 삼을지. 응답 상한이 {@code size}이므로
	 *     그보다 많은 후보는 애초에 최종 결과에 전부 들어갈 수 없다
	 * @return 문자열 목록 순위 순서의 매치 목록. 매치가 없으면 빈 목록
	 */
	public List<LexicalMatch> findMatches(long memberId, String query, int limit) {
		return jdbc.query(MATCH_SQL,
			Map.of("memberId", memberId, "pattern", "%" + escapeLike(query) + "%", "limit", limit),
			(rows, i) -> new LexicalMatch(rows.getLong("record_id"), rows.getLong("context_id")));
	}

	/**
	 * {@code %}·{@code _}·{@code \}는 LIKE 문법 글자다. 질의에 섞여 오면 <b>글자</b>로 취급해야
	 * 한다 — 이스케이프가 빠지면 {@code 50%} 같은 질의가 「50으로 시작하는 모든 본문」에 매치되어
	 * 게이트가 정한 자격(부분일치)보다 넓은 후보가 들어온다.
	 */
	private static String escapeLike(String query) {
		return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
