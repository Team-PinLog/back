package com.pinlog.pinlogback.domain.place.entity;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 공용 장소 스냅샷(데이터모델 2.3). 식별 기준은 kakao_place_id이며 저장 후 갱신하지 않는다.
 *
 * <p>공용 데이터라 삭제하지 않으므로 deleted_at이 없고, 그래서 BaseEntity를 상속하지 않는다.
 * 감사 리스너는 상속되지 않으므로 {@code @EntityListeners}를 직접 붙인다(데이터베이스 규약) —
 * 빠뜨리면 created_at이 null로 INSERT되어 NOT NULL 위반으로 실패한다.
 */
@Entity
@Table(name = "place", schema = "core")
@EntityListeners(AuditingEntityListener.class)
public class Place {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "kakao_place_id", nullable = false, updatable = false, length = 50)
	private String kakaoPlaceId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "address", nullable = false, length = 200)
	private String address;

	@Column(name = "road_address", length = 200)
	private String roadAddress;

	@Column(name = "phone", length = 30)
	private String phone;

	@Column(name = "place_url", length = 300)
	private String placeUrl;

	// 카카오 로컬 응답에 없는 값이라 생성 경로가 채우지 않는다. 시연용 목업 단계에서는
	// SQL로 수동 연결하며, 이후 카카오 이미지 검색 API 전환 시 외부 URL이 들어간다(2.3).
	@Column(name = "thumbnail_url", length = 300)
	private String thumbnailUrl;

	@Column(name = "lat", nullable = false, precision = 10, scale = 7)
	private BigDecimal lat;

	@Column(name = "lng", nullable = false, precision = 10, scale = 7)
	private BigDecimal lng;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Place() {
	}

	private Place(String kakaoPlaceId, String name, String address, String roadAddress,
		String phone, String placeUrl, BigDecimal lat, BigDecimal lng) {
		this.kakaoPlaceId = kakaoPlaceId;
		this.name = name;
		this.address = address;
		this.roadAddress = roadAddress;
		this.phone = phone;
		this.placeUrl = placeUrl;
		this.lat = lat;
		this.lng = lng;
	}

	public static Place create(String kakaoPlaceId, String name, String address, String roadAddress,
		String phone, String placeUrl, BigDecimal lat, BigDecimal lng) {
		return new Place(kakaoPlaceId, name, address, roadAddress, phone, placeUrl, lat, lng);
	}

	public Long getId() {
		return id;
	}

	public String getKakaoPlaceId() {
		return kakaoPlaceId;
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public String getRoadAddress() {
		return roadAddress;
	}

	public String getPhone() {
		return phone;
	}

	public String getPlaceUrl() {
		return placeUrl;
	}

	public String getThumbnailUrl() {
		return thumbnailUrl;
	}

	public BigDecimal getLat() {
		return lat;
	}

	public BigDecimal getLng() {
		return lng;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
