package com.pinlog.pinlogback.domain.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.entity.CollectionRecord;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRecordRepository;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.place.entity.Place;
import com.pinlog.pinlogback.domain.place.repository.PlaceRepository;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@SpringBootTest(properties = "management.health.redis.enabled=false")
class CollectionPersistenceTests extends PostgresContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private CollectionRepository collectionRepository;

	@Autowired
	private CollectionRecordRepository collectionRecordRepository;

	@Test
	void savedCollectionGetsAuditedCreatedAtAndUpdatedAt() {
		Member member = memberRepository.save(Member.create());

		Collection saved = collectionRepository.save(Collection.create(member.getId(), "비 오는 날의 카페"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
		assertThat(saved.isPublished()).isTrue();
		assertThat(saved.getRecordCount()).isZero();
	}

	@Test
	void savedCollectionRecordGetsAuditedCreatedAt() {
		Member member = memberRepository.save(Member.create());
		Collection collection = collectionRepository.save(Collection.create(member.getId(), "카페"));
		Record record = anyRecord(member, "colrec-audit-1");

		CollectionRecord saved = collectionRecordRepository.save(
			CollectionRecord.create(collection.getId(), record.getId()));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	@Test
	void duplicateActiveCollectionRecordIsRejectedByPartialUniqueIndex() {
		Member member = memberRepository.save(Member.create());
		Collection collection = collectionRepository.save(Collection.create(member.getId(), "카페"));
		Record record = anyRecord(member, "colrec-dup-1");

		collectionRecordRepository.saveAndFlush(CollectionRecord.create(collection.getId(), record.getId()));

		assertThatThrownBy(() ->
			collectionRecordRepository.saveAndFlush(CollectionRecord.create(collection.getId(), record.getId())))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Record anyRecord(Member member, String kakaoPlaceId) {
		Place place = placeRepository.save(Place.create(
			kakaoPlaceId,
			"장소",
			"지번 주소",
			null,
			null,
			null,
			new BigDecimal("37.5000000"),
			new BigDecimal("127.0000000")
		));
		return recordRepository.save(Record.create(member.getId(), place.getId()));
	}
}
