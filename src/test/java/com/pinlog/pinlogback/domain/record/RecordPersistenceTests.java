package com.pinlog.pinlogback.domain.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

@SpringBootTest
class RecordPersistenceTests extends IntegrationContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Test
	void savedRecordGetsAuditedCreatedAtAndUpdatedAt() {
		Member member = memberRepository.save(Member.create());
		Place place = placeRepository.save(anyPlace("record-audit-1"));

		Record saved = recordRepository.save(Record.create(member.getId(), place.getId()));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
		assertThat(saved.isDeleted()).isFalse();
	}

	@Test
	void secondActiveRecordForSameMemberAndPlaceIsRejectedByPartialUniqueIndex() {
		Member member = memberRepository.save(Member.create());
		Place place = placeRepository.save(anyPlace("record-dup-1"));

		recordRepository.saveAndFlush(Record.create(member.getId(), place.getId()));

		assertThatThrownBy(() ->
			recordRepository.saveAndFlush(Record.create(member.getId(), place.getId())))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void softDeletedRecordAllowsNewActiveRecordForSamePlace() {
		Member member = memberRepository.save(Member.create());
		Place place = placeRepository.save(anyPlace("record-dup-2"));

		Record first = recordRepository.save(Record.create(member.getId(), place.getId()));
		first.softDelete();
		recordRepository.saveAndFlush(first);

		Record second = recordRepository.saveAndFlush(Record.create(member.getId(), place.getId()));

		assertThat(second.getId()).isNotEqualTo(first.getId());
	}

	private Place anyPlace(String kakaoPlaceId) {
		return Place.create(
			kakaoPlaceId,
			"장소",
			"지번 주소",
			null,
			null,
			null,
			new BigDecimal("37.5000000"),
			new BigDecimal("127.0000000")
		);
	}
}
