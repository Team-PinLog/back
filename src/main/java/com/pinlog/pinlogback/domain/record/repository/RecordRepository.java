package com.pinlog.pinlogback.domain.record.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
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

	/** 탈퇴 연쇄 삭제용. 그 회원의 활성 Record 전체다(데이터모델 6.9). */
	List<Record> findByMemberId(Long memberId);

	/**
	 * 마이페이지 요약의 활성 Record 수(API 명세 3.5).
	 *
	 * <p>{@code @SQLRestriction("deleted_at IS NULL")}이 <b>count 쿼리에도 적용된다</b> — 파생
	 * 쿼리로 두는 것으로 활성 기준 집계가 성립한다. `@Query`로 조건을 명시할 필요가 없고,
	 * 명시하려다 조건을 빠뜨리면 오히려 삭제분이 섞인다(S15P11A705-200에서 뮤테이션으로 확인).
	 */
	long countByMemberId(Long memberId);

	List<Record> findByIdInAndMemberId(List<Long> ids, Long memberId);

	/**
	 * 최근 Record 목록의 첫 페이지(API 명세 5.9). {@code since} 이후에 만들어진 내 활성 Record를
	 * 최신순으로 준다.
	 *
	 * <p>삭제 조건을 적지 않는다. {@code @SQLRestriction("deleted_at IS NULL")}이 이미 걸리며,
	 * 명시하려다 빠뜨리면 오히려 삭제분이 섞인다(S15P11A705-200에서 뮤테이션으로 확인).
	 *
	 * <p><b>{@code id} 정렬이 동률을 끊는다.</b> {@code created_at}은 DB {@code now()}가 채우므로 한
	 * 트랜잭션에서 만들어진 Record들이 같은 값을 가질 수 있고, 그때 이 조건이 없으면 페이지 경계에서
	 * 항목이 중복되거나 사라진다.
	 */
	@Query("select r from Record r"
		+ " where r.memberId = :memberId and r.createdAt >= :since"
		+ " order by r.createdAt desc, r.id desc")
	List<Record> findRecentFirstPage(@Param("memberId") Long memberId, @Param("since") Instant since,
		Pageable pageable);

	/**
	 * 최근 Record 목록의 커서 이후 페이지. 정렬키 쌍 {@code (createdAt, id)}보다 <b>작은</b> 것만
	 * 읽는다 — 첫 페이지와 같은 정렬이므로 이미 준 항목이 다시 나오지 않는다.
	 *
	 * <p>{@code since}는 요청마다 다시 계산되어 창의 뒤쪽 경계가 앞으로 밀린다. 최신순이라 밀려서
	 * 빠지는 것은 아직 주지 않은 뒤쪽뿐이고, 이미 준 항목이 중복되지는 않는다(명세 5.9).
	 */
	@Query("select r from Record r"
		+ " where r.memberId = :memberId and r.createdAt >= :since"
		+ " and (r.createdAt < :cursorCreatedAt"
		+ " or (r.createdAt = :cursorCreatedAt and r.id < :cursorId))"
		+ " order by r.createdAt desc, r.id desc")
	List<Record> findRecentAfter(@Param("memberId") Long memberId, @Param("since") Instant since,
		@Param("cursorCreatedAt") Instant cursorCreatedAt, @Param("cursorId") Long cursorId,
		Pageable pageable);

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

	/**
	 * 지도 마커(API 명세 4.2). {@code keyword}는 서비스가 소문자화·이스케이프·{@code %} 감싸기까지
	 * 마친 완성된 LIKE 패턴이며, {@code null}이면 필터하지 않는다 — 가공을 쿼리의 {@code concat}이
	 * 아니라 서비스에 두는 이유는 null 파라미터 때문이다. {@code lower(concat('%', :keyword, '%'))}
	 * 안의 null은 Postgres가 타입을 추론하지 못해 {@code lower(bytea)} 오류가 되고, {@code like}의
	 * 우변 자리는 text로 고정되어 안전하다.
	 *
	 * <p>keyword 조건에 인덱스를 붙이지 않는다. 이 쿼리는 record.member_id로 드라이빙하고 place는
	 * PK 조인이라, LIKE는 이미 회원 단위로 좁혀진 수십~수백 행에 필터로만 적용된다.
	 *
	 * <p><b>정렬은 여기서 하지 않는다.</b> 이름순은 DB collation에 따라 결과가 달라진다 — 한글에
	 * 동순위 가중치를 주는 collation에서는 {@code ORDER BY p.name}이 사실상 무순서다. 환경에
	 * 좌우되지 않도록 서비스가 Java {@code Collator}로 정렬한다.
	 */
	@Query("select new com.pinlog.pinlogback.domain.record.dto.MapMarkerResponse("
		+ "r.id, p.id, p.name, p.lat, p.lng)"
		+ " from Record r join Place p on p.id = r.placeId"
		+ " where r.memberId = :memberId"
		+ " and (:keyword is null"
		+ " or lower(p.name) like :keyword escape '!'"
		+ " or lower(p.address) like :keyword escape '!')")
	List<MapMarkerResponse> findMarkers(@Param("memberId") Long memberId, @Param("keyword") String keyword);

	@Query("select new com.pinlog.pinlogback.domain.record.dto.MapMarkerResponse("
		+ "r.id, p.id, p.name, p.lat, p.lng)"
		+ " from Record r join Place p on p.id = r.placeId"
		+ " where r.memberId = :memberId"
		+ " and p.lat between :swLat and :neLat"
		+ " and p.lng between :swLng and :neLng"
		+ " and (:keyword is null"
		+ " or lower(p.name) like :keyword escape '!'"
		+ " or lower(p.address) like :keyword escape '!')")
	List<MapMarkerResponse> findMarkersWithinBounds(
		@Param("memberId") Long memberId,
		@Param("swLat") BigDecimal swLat,
		@Param("swLng") BigDecimal swLng,
		@Param("neLat") BigDecimal neLat,
		@Param("neLng") BigDecimal neLng,
		@Param("keyword") String keyword);
}
