package com.pinlog.pinlogback.domain.record.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pinlog.pinlogback.domain.record.dto.MapMarkerResponse;
import com.pinlog.pinlogback.domain.record.entity.Record;

import jakarta.persistence.LockModeType;

public interface RecordRepository extends JpaRepository<Record, Long> {

	Optional<Record> findByMemberIdAndPlaceId(Long memberId, Long placeId);

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
