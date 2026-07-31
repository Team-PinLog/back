package com.pinlog.pinlogback.domain.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

@SpringBootTest
class CollectionPersistenceTests extends IntegrationContainerSupport {

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

	/**
	 * {@code RecordDeletionService.cascadeDelete}가 이 순서대로 Collection을 잠그므로 락 순서가
	 * 곧 교착 여부다(BI-12). 계약을 실행 가능한 형태로 적어 둔다.
	 *
	 * <p><b>이 테스트는 정렬을 없애도 오늘은 통과한다.</b> 지금 실행 계획이
	 * {@code uq_colrec_active (collection_id, record_id)}를 훑어 {@code collection_id} 순서를
	 * 공짜로 주기 때문이다 — 링크를 12개까지 늘려도 같았다. 보증 자체는 쿼리 이름이 들고 있고,
	 * 이 테스트는 그 계약이 무엇인지 읽을 수 있게 남긴다.
	 */
	@Test
	void collectionRecordsOfOneRecordAreOrderedByCollectionId() {
		Member member = memberRepository.save(Member.create());
		Collection lower = collectionRepository.save(Collection.create(member.getId(), "먼저 만든 컬렉션"));
		Collection higher = collectionRepository.save(Collection.create(member.getId(), "나중에 만든 컬렉션"));
		Record record = anyRecord(member, "colrec-lock-order-1");

		// collectionId 역순으로 넣는다.
		collectionRecordRepository.saveAndFlush(CollectionRecord.create(higher.getId(), record.getId()));
		collectionRecordRepository.saveAndFlush(CollectionRecord.create(lower.getId(), record.getId()));

		List<Long> lockOrder = collectionRecordRepository
			.findByRecordIdOrderByCollectionIdAsc(record.getId()).stream()
			.map(CollectionRecord::getCollectionId)
			.toList();

		assertThat(lockOrder).containsExactly(lower.getId(), higher.getId());
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
