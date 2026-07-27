package com.pinlog.pinlogback.domain.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@Testcontainers
@SpringBootTest(properties = "management.health.redis.enabled=false")
class MemberSoftDeleteTests extends PostgresContainerSupport {

	@Container
	static final PostgreSQLContainer<?> postgres = POSTGRES;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void repositoryDeleteMarksDeletedAtInsteadOfRemovingRow() {
		Member saved = memberRepository.save(Member.create());
		Long id = saved.getId();

		memberRepository.delete(saved);
		memberRepository.flush();

		Long physicalRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.member WHERE id = ?", Long.class, id);
		Long deletedRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.member WHERE id = ? AND deleted_at IS NOT NULL", Long.class, id);

		assertThat(physicalRows).isEqualTo(1L);
		assertThat(deletedRows).isEqualTo(1L);
	}

	@Test
	void deletedMemberIsExcludedFromQueries() {
		Member saved = memberRepository.save(Member.create());
		Long id = saved.getId();

		memberRepository.delete(saved);
		memberRepository.flush();
		memberRepository.findAll();

		assertThat(memberRepository.findById(id)).isEmpty();
	}

	@Test
	void softDeleteHelperMarksEntityAsDeleted() {
		Member saved = memberRepository.save(Member.create());

		saved.softDelete();
		memberRepository.saveAndFlush(saved);

		assertThat(saved.isDeleted()).isTrue();
		assertThat(saved.getDeletedAt()).isNotNull();
	}
}
