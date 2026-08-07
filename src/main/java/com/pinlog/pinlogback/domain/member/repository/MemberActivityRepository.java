package com.pinlog.pinlogback.domain.member.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pinlog.pinlogback.domain.member.dto.MeActivityResponse.AreaCount;
import com.pinlog.pinlogback.domain.member.dto.MeActivityResponse.BusiestDay;
import com.pinlog.pinlogback.domain.member.dto.MeActivityResponse.MonthCount;

/**
 * 나의 활동 기록 집계 쿼리(S15P11A705-397). 전부 {@code core} 스키마만 읽는다.
 *
 * <p>JPQL이 아니라 SQL로 두는 이유는 세 가지다 — {@code date_trunc}·{@code split_part} 같은
 * PostgreSQL 함수가 필요하고, 결과가 엔티티가 아니라 집계 투영이며, 아래 {@code MY_RECORDS} CTE를
 * 네 쿼리가 공유해 <b>대상 집합의 정의를 한 곳에 두기</b> 위해서다.
 *
 * <p><b>{@code AT TIME ZONE 'Asia/Seoul'}이 이 클래스의 핵심이다.</b> {@code created_at}은
 * {@code TIMESTAMPTZ}라 그냥 {@code date_trunc}하면 서버 타임존을 탄다. KST 자정 직후에 남긴
 * 기록(= UTC로는 전날 오후)이 전날에 붙어 "가장 붐빈 하루"가 하루씩 밀린다.
 *
 * <p><b>삭제 조건을 SQL에 직접 적는다.</b> 엔티티를 거치지 않으므로 {@code @SQLRestriction}이
 * 걸리지 않는다 — JPA 리포지토리에서 조건을 생략해도 되는 것과 정반대다.
 */
@Repository
public class MemberActivityRepository {

	/**
	 * 집계 대상 집합. 내 활성 Record에 장소를 붙이고 KST 벽시계 시각을 미리 만들어 둔다.
	 *
	 * <p>{@code district}를 여기서 한 번만 뽑는다. 주소는 "서울 마포구 성미산로 198"처럼 공백으로
	 * 끊긴 문자열이라 두 번째 조각이 시·구다. {@code nullif(..., '')}로 조각이 없는 주소를
	 * {@code NULL}로 만들어 집계에서 자연히 빠지게 한다 — 도로명·지번이 섞이거나 형식을 벗어난
	 * 주소는 이 방식으로 정확히 뽑히지 않으며, 그 한계를 안고 쓰는 값이다(티켓 상세 내용).
	 */
	private static final String MY_RECORDS = """
		WITH my AS (
			SELECT r.id AS record_id,
				r.created_at AS created_at,
				p.name AS place_name,
				(r.created_at AT TIME ZONE 'Asia/Seoul') AS local_ts,
				nullif(split_part(p.address, ' ', 2), '') AS district
			FROM core.record r
			JOIN core.place p ON p.id = r.place_id
			WHERE r.member_id = :memberId
				AND r.deleted_at IS NULL
		)
		""";

	private static final String TOTALS_SQL = MY_RECORDS + """
		SELECT count(*) AS place_count,
			count(DISTINCT district) AS district_count,
			min(local_ts)::date AS first_recorded_on,
			count(DISTINCT date_trunc('month', local_ts)) AS recorded_month_count
		FROM my
		""";

	/** 기록이 있는 달만 준다. 빈 달 채우기는 서비스가 한다 — SQL로 하면 이번 달을 DB 시계가 정한다. */
	private static final String MONTHS_SQL = MY_RECORDS + """
		SELECT to_char(date_trunc('month', local_ts), 'YYYY-MM') AS month,
			count(*) AS record_count
		FROM my
		GROUP BY 1
		ORDER BY 1
		""";

	/**
	 * 동점을 {@code COLLATE "C"}로 끊는다. 한글 이름을 DB 기본 collation으로 정렬하면 운영 DB와
	 * 테스트 컨테이너의 locale이 달라 순서가 갈린다 — S15P11A705-388이 같은 함정을 만나
	 * 이름 정렬 자체를 피했다. 여기서는 이름 말고 끊을 키가 없으므로 collation을 명시해 고정한다.
	 */
	private static final String AREAS_SQL = MY_RECORDS + """
		SELECT district, count(*) AS record_count
		FROM my
		WHERE district IS NOT NULL
		GROUP BY district
		ORDER BY count(*) DESC, district COLLATE "C" ASC
		LIMIT 5
		""";

	/** 동점이면 더 최근 날짜를 고른다 — 지난 기록보다 최근 기록이 화면에서 더 말이 된다. */
	private static final String BUSIEST_DAY_SQL = MY_RECORDS + """
		SELECT local_ts::date AS day, count(*) AS record_count
		FROM my
		GROUP BY 1
		ORDER BY count(*) DESC, 1 DESC
		LIMIT 1
		""";

	/**
	 * 처음·마지막 기록의 장소 이름을 한 번에 가져온다.
	 *
	 * <p>{@code id}가 동률을 끊는다. {@code created_at}은 DB {@code now()}가 채우므로 한
	 * 트랜잭션에서 만들어진 Record들이 같은 값을 가질 수 있고, 그때 이름이 회차마다 갈린다.
	 */
	private static final String FIRST_LAST_PLACE_SQL = MY_RECORDS + """
		SELECT
			(SELECT place_name FROM my ORDER BY created_at ASC, record_id ASC LIMIT 1) AS first_place_name,
			(SELECT place_name FROM my ORDER BY created_at DESC, record_id DESC LIMIT 1) AS last_place_name
		""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public MemberActivityRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Totals findTotals(Long memberId) {
		return jdbcTemplate.queryForObject(TOTALS_SQL, params(memberId), (rs, rowNum) -> {
			java.sql.Date firstOn = rs.getDate("first_recorded_on");
			return new Totals(
				rs.getLong("place_count"),
				rs.getLong("district_count"),
				firstOn == null ? null : firstOn.toLocalDate(),
				rs.getLong("recorded_month_count"));
		});
	}

	/** 기록이 있는 달만, 오래된 순. */
	public List<MonthCount> findRecordedMonths(Long memberId) {
		return jdbcTemplate.query(MONTHS_SQL, params(memberId), (rs, rowNum) ->
			new MonthCount(rs.getString("month"), rs.getLong("record_count")));
	}

	public List<AreaCount> findTopAreas(Long memberId) {
		return jdbcTemplate.query(AREAS_SQL, params(memberId), (rs, rowNum) ->
			new AreaCount(rs.getString("district"), rs.getLong("record_count")));
	}

	public Optional<BusiestDay> findBusiestDay(Long memberId) {
		return jdbcTemplate.query(BUSIEST_DAY_SQL, params(memberId), (rs, rowNum) ->
				new BusiestDay(rs.getDate("day").toLocalDate(), rs.getLong("record_count")))
			.stream()
			.findFirst();
	}

	public FirstLastPlace findFirstAndLastPlace(Long memberId) {
		return jdbcTemplate.queryForObject(FIRST_LAST_PLACE_SQL, params(memberId), (rs, rowNum) ->
			new FirstLastPlace(rs.getString("first_place_name"), rs.getString("last_place_name")));
	}

	private MapSqlParameterSource params(Long memberId) {
		return new MapSqlParameterSource(Map.of("memberId", memberId));
	}

	/**
	 * {@code TOTALS_SQL} 한 번의 결과. 응답 DTO가 아니라 쿼리 결과라서 별도 타입으로 둔다 —
	 * {@code recordedMonthCount}는 응답에서 {@code counts} 쪽으로 갈라져 담긴다.
	 */
	public record Totals(long placeCount, long districtCount, LocalDate firstRecordedOn,
		long recordedMonthCount) {
	}

	/** 둘 다 기록이 없으면 {@code null}이다. */
	public record FirstLastPlace(String firstPlaceName, String lastPlaceName) {
	}
}
