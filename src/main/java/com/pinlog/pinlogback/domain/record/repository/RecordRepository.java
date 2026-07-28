package com.pinlog.pinlogback.domain.record.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pinlog.pinlogback.domain.record.dto.MapMarkerResponse;
import com.pinlog.pinlogback.domain.record.entity.Record;

import jakarta.persistence.LockModeType;

public interface RecordRepository extends JpaRepository<Record, Long> {

	Optional<Record> findByMemberIdAndPlaceId(Long memberId, Long placeId);

	List<Record> findByIdInAndMemberId(List<Long> ids, Long memberId);

	/**
	 * 동시 요청에 안전한 Record 확보(데이터모델 6.1·6.3). Place upsert와 같은 패턴이며 이유도 같다 —
	 * 조회 후 저장하면 두 트랜잭션이 나란히 "없음"으로 판정해 uq_record_active를 위반한다.
	 *
	 * <p>반환값이 곧 분기 조건이다. <b>1이면 이 요청이 Record를 만들었고(RECORD_CREATED),
	 * 0이면 활성 Record가 이미 있었다(CONTEXT_ADDED).</b> 어느 쪽이든 호출부는 재조회한 Record에
	 * Context를 붙이므로 순차 경로와 동시 경로가 같은 결과로 수렴한다.
	 *
	 * <p>{@code ON CONFLICT}에 인덱스 술어({@code WHERE deleted_at IS NULL})를 함께 적는다.
	 * uq_record_active가 활성행 부분 유니크라, 술어를 빼면 Postgres가 대상 인덱스를 추론하지 못한다.
	 *
	 * <p>감사 리스너 대신 DB {@code now()}로 시각을 채운다(place와 동일). 네이티브 INSERT라
	 * {@code @CreatedDate}가 개입하지 않는다.
	 */
	@Modifying
	@Query(value = "INSERT INTO core.record (member_id, place_id, created_at, updated_at)"
		+ " VALUES (:memberId, :placeId, now(), now())"
		+ " ON CONFLICT (member_id, place_id) WHERE deleted_at IS NULL DO NOTHING", nativeQuery = true)
	int insertIfAbsent(@Param("memberId") Long memberId, @Param("placeId") Long placeId);

	/**
	 * 활성 Context 수를 세고 분기한 뒤 쓰는 유스케이스(수정·삭제)의 행 잠금(데이터모델 6.4~6.6).
	 * 서로 다른 Context를 다루는 두 트랜잭션이 공유하는 유일한 대상이 부모 Record이므로 여기를 잠근다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Record r where r.id = :recordId")
	Optional<Record> findByIdForUpdate(@Param("recordId") Long recordId);

	@Query("select new com.pinlog.pinlogback.domain.record.dto.MapMarkerResponse("
		+ "r.id, p.id, p.name, p.lat, p.lng)"
		+ " from Record r join Place p on p.id = r.placeId"
		+ " where r.memberId = :memberId")
	List<MapMarkerResponse> findMarkers(@Param("memberId") Long memberId);

	@Query("select new com.pinlog.pinlogback.domain.record.dto.MapMarkerResponse("
		+ "r.id, p.id, p.name, p.lat, p.lng)"
		+ " from Record r join Place p on p.id = r.placeId"
		+ " where r.memberId = :memberId"
		+ " and p.lat between :swLat and :neLat"
		+ " and p.lng between :swLng and :neLng")
	List<MapMarkerResponse> findMarkersWithinBounds(
		@Param("memberId") Long memberId,
		@Param("swLat") BigDecimal swLat,
		@Param("swLng") BigDecimal swLng,
		@Param("neLat") BigDecimal neLat,
		@Param("neLng") BigDecimal neLng);
}
