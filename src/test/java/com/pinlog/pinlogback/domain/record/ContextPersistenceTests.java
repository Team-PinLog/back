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
import com.pinlog.pinlogback.domain.record.entity.Context;
import com.pinlog.pinlogback.domain.record.entity.Record;
import com.pinlog.pinlogback.domain.record.repository.ContextRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

@SpringBootTest(properties = "management.health.redis.enabled=false")
class ContextPersistenceTests extends IntegrationContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PlaceRepository placeRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private ContextRepository contextRepository;

	@Test
	void firstContextGetsOriginCreatedAtEqualToItsCreatedAt() {
		Record record = anyRecord("context-origin-1");

		Context saved = contextRepository.saveAndFlush(
			Context.create(record.getId(), record.getMemberId(), "비 오는 날 가려고 저장"));

		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getOriginCreatedAt()).isEqualTo(saved.getCreatedAt());
	}

	@Test
	void replacingContextInheritsOriginCreatedAtFromOldContext() {
		Record record = anyRecord("context-origin-2");
		Context old = contextRepository.saveAndFlush(
			Context.create(record.getId(), record.getMemberId(), "처음 저장 이유"));

		Context replacement = contextRepository.saveAndFlush(Context.replacing(old, "수정된 저장 이유"));

		assertThat(replacement.getId()).isNotEqualTo(old.getId());
		assertThat(replacement.getOriginCreatedAt()).isEqualTo(old.getOriginCreatedAt());
		assertThat(replacement.getCreatedAt()).isNotEqualTo(old.getOriginCreatedAt());
	}

	@Test
	void blankBodyIsRejectedByCheckConstraint() {
		Record record = anyRecord("context-blank-1");

		assertThatThrownBy(() ->
			contextRepository.saveAndFlush(Context.create(record.getId(), record.getMemberId(), "   ")))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Record anyRecord(String kakaoPlaceId) {
		Member member = memberRepository.save(Member.create());
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
