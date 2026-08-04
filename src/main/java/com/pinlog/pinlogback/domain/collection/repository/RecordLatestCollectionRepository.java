package com.pinlog.pinlogback.domain.collection.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Record별 "가장 최근에 담긴" Collection id를 한 번의 질의로 모은다(API 명세 4.2,
 * S15P11A705-308). 지도 마커 색상 구분용이라 마커마다 반복 조회하면 그대로 N+1이다.
 *
 * <p>"가장 최근"의 기준은 컬렉션 내부 정렬(데이터모델 2.7)과 같은 담은 시각
 * ({@code collection_record.created_at DESC}, 동시각이면 {@code id DESC})이다 — Collection의
 * 생성·수정 시각이 아니다. {@code DISTINCT ON}은 JPQL에 없어 native가 필요하고, native는
 * {@code @SQLRestriction} 밖이므로 소프트 삭제 제외({@code deleted_at IS NULL})를 직접 적는다.
 */
@Repository
public class RecordLatestCollectionRepository {

	private static final String LATEST_COLLECTION_IDS_SQL = """
		SELECT DISTINCT ON (cr.record_id) cr.record_id, cr.collection_id
		FROM core.collection_record cr
		WHERE cr.record_id IN (:recordIds)
			AND cr.deleted_at IS NULL
		ORDER BY cr.record_id, cr.created_at DESC, cr.id DESC
		""";

	private final NamedParameterJdbcTemplate jdbc;

	public RecordLatestCollectionRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @return Record id → 가장 최근에 담긴 Collection id. <b>어느 Collection에도 담기지 않은
	 *     Record는 키가 없다</b> — 호출부가 {@code null}로 채운다
	 */
	public Map<Long, Long> findLatestCollectionIds(List<Long> recordIds) {
		if (recordIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Long> byRecord = new LinkedHashMap<>();
		jdbc.query(LATEST_COLLECTION_IDS_SQL, Map.of("recordIds", recordIds), rows -> {
			byRecord.put(rows.getLong("record_id"), rows.getLong("collection_id"));
		});
		return byRecord;
	}
}
