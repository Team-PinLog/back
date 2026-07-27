package com.pinlog.pinlogback.domain.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@SpringBootTest(properties = "management.health.redis.enabled=false")
class MemberPersistenceTests extends PostgresContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Test
	void savedMemberGetsIdAndAuditedCreatedAt() {
		Member saved = memberRepository.save(Member.create());

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.isDeleted()).isFalse();
		assertThat(saved.getDeletedAt()).isNull();
	}

	@Test
	void savedMemberIsFoundById() {
		Member saved = memberRepository.save(Member.create());

		assertThat(memberRepository.findById(saved.getId())).isPresent();
	}
}
