package com.pinlog.pinlogback.domain.place.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.place.entity.Place;

public interface PlaceRepository extends JpaRepository<Place, Long> {

	Optional<Place> findByKakaoPlaceId(String kakaoPlaceId);
}
