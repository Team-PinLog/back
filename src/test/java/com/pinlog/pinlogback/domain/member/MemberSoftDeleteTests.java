package com.pinlog.pinlogback.domain.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

/**
 * soft delete의 두 경로를 각각 고정한다.
 *
 * <p>정식 경로는 {@code BaseEntity.softDelete()}이고, {@code @SQLDelete}는 실수로
 * {@code repository.delete()}나 cascade가 호출됐을 때 물리 삭제를 막는 <b>안전망</b>이다. 역할이 다르므로
 * 테스트도 나눠 둔다 — 안전망 쪽 테스트가 깨지면 "실수해도 행이 남는다"는 보장이 사라진 것이고, 정식
 * 경로 쪽이 깨지면 도메인 코드가 쓰는 API가 깨진 것이다(규약은 database-conventions.md).
 */
@SpringBootTest
class MemberSoftDeleteTests extends IntegrationContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void softDeleteRecordsDeletedAtAndExcludesFromQueries() {
		Member saved = memberRepository.save(Member.create());
		Long id = saved.getId();

		saved.softDelete();
		memberRepository.saveAndFlush(saved);

		assertThat(deletedRowCount(id)).isEqualTo(1L);
		assertThat(memberRepository.findById(id)).isEmpty();
	}

	/**
	 * 정식 경로를 {@code softDelete()}로 정한 이유가 여기 있다 — 호출 직후 메모리의 엔티티가 이미
	 * 삭제된 상태로 보인다. 아래 {@link #repositoryDeleteLeavesTheInMemoryEntityStale()}와 대조된다.
	 */
	@Test
	void softDeleteLeavesTheInMemoryEntityConsistent() {
		Member saved = memberRepository.save(Member.create());

		saved.softDelete();
		memberRepository.saveAndFlush(saved);

		assertThat(saved.isDeleted()).isTrue();
		assertThat(saved.getDeletedAt()).isNotNull();
	}

	@Test
	void repositoryDeleteDoesNotRemoveTheRow() {
		Member saved = memberRepository.save(Member.create());
		Long id = saved.getId();

		memberRepository.delete(saved);
		memberRepository.flush();

		assertThat(physicalRowCount(id))
			.as("안전망이 동작하면 행 자체는 남아 있어야 한다")
			.isEqualTo(1L);
		assertThat(deletedRowCount(id)).isEqualTo(1L);
	}

	/**
	 * 안전망은 DB 행만 갱신하므로 영속성 컨텍스트의 필드는 그대로다. 이 함정 때문에
	 * {@code repository.delete()}를 정식 경로로 쓰지 않는다 — 호출 뒤 같은 인스턴스를 보고 판단하는
	 * 코드가 조용히 틀린다.
	 */
	@Test
	void repositoryDeleteLeavesTheInMemoryEntityStale() {
		Member saved = memberRepository.save(Member.create());

		memberRepository.delete(saved);
		memberRepository.flush();

		assertThat(saved.isDeleted())
			.as("@SQLDelete는 DB 행만 갱신한다 — 이것이 softDelete()를 정식 경로로 두는 이유다")
			.isFalse();
	}

	@Test
	void deletedMemberIsExcludedFromQueries() {
		Member saved = memberRepository.save(Member.create());
		Long id = saved.getId();

		memberRepository.delete(saved);
		memberRepository.flush();

		assertThat(memberRepository.findById(id)).isEmpty();
		assertThat(memberRepository.findAll()).noneMatch(member -> id.equals(member.getId()));
	}

	private Long physicalRowCount(Long id) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.member WHERE id = ?", Long.class, id);
	}

	private Long deletedRowCount(Long id) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.member WHERE id = ? AND deleted_at IS NOT NULL", Long.class, id);
	}
}
