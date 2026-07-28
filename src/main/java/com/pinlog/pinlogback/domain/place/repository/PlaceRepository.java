package com.pinlog.pinlogback.domain.place.repository;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pinlog.pinlogback.domain.place.entity.Place;

public interface PlaceRepository extends JpaRepository<Place, Long> {

	Optional<Place> findByKakaoPlaceId(String kakaoPlaceId);

	/**
	 * 동시 요청에 안전한 upsert(데이터모델 6.1). 이미 있으면 아무것도 하지 않는다 —
	 * Place는 저장 시점 스냅샷이므로 전달값으로 기존 행을 갱신하지 않는다(데이터모델 2.3).
	 * 호출 후 {@link #findByKakaoPlaceId}로 재조회한다.
	 */
	@Modifying
	@Query(value = "INSERT INTO core.place"
		+ " (kakao_place_id, name, address, road_address, phone, place_url, lat, lng, created_at, updated_at)"
		+ " VALUES (:kakaoPlaceId, :name, :address, :roadAddress, :phone, :placeUrl, :lat, :lng, now(), now())"
		+ " ON CONFLICT (kakao_place_id) DO NOTHING", nativeQuery = true)
	int insertIfAbsent(
		@Param("kakaoPlaceId") String kakaoPlaceId,
		@Param("name") String name,
		@Param("address") String address,
		@Param("roadAddress") String roadAddress,
		@Param("phone") String phone,
		@Param("placeUrl") String placeUrl,
		@Param("lat") BigDecimal lat,
		@Param("lng") BigDecimal lng);
}
