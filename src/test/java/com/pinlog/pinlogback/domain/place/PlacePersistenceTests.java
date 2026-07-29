package com.pinlog.pinlogback.domain.place;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * place는 BaseEntity를 상속하지 않으므로(deleted_at 없음) 감사 리스너를 엔티티에 직접 붙여야 한다.
 * 붙이지 않으면 created_at이 null로 INSERT되어 NOT NULL 위반으로 실패한다(데이터베이스 규약).
 */
@SpringBootTest
class PlacePersistenceTests extends IntegrationContainerSupport {

	@Autowired
	private PlaceRepository placeRepository;

	@Test
	void savedPlaceGetsAuditedCreatedAtAndUpdatedAt() {
		Place saved = placeRepository.save(Place.create(
			"kakao-audit-1",
			"앤트러사이트 성수",
			"성동구 성수동2가 273-1",
			"성동구 연무장길 47",
			"02-1234-5678",
			"http://place.map.kakao.com/1234567",
			new BigDecimal("37.5447000"),
			new BigDecimal("127.0557000")
		));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
	}

	@Test
	void optionalFieldsMayBeNull() {
		Place saved = placeRepository.save(Place.create(
			"kakao-audit-2",
			"이름만 있는 장소",
			"지번 주소",
			null,
			null,
			null,
			new BigDecimal("35.0000000"),
			new BigDecimal("128.0000000")
		));

		assertThat(saved.getRoadAddress()).isNull();
		assertThat(saved.getPhone()).isNull();
		assertThat(saved.getPlaceUrl()).isNull();
	}
}
