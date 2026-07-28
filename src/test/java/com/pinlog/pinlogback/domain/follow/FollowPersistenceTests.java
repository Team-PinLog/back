package com.pinlog.pinlogback.domain.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.pinlog.pinlogback.domain.follow.entity.Follow;
import com.pinlog.pinlogback.domain.follow.repository.FollowRepository;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

@SpringBootTest(properties = "management.health.redis.enabled=false")
class FollowPersistenceTests extends PostgresContainerSupport {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private FollowRepository followRepository;

	@Test
	void savedFollowGetsAuditedCreatedAt() {
		Member followee = memberRepository.save(Member.create());
		Member follower = memberRepository.save(Member.create());

		Follow saved = followRepository.save(Follow.create(followee.getId(), follower.getId()));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getDisplayName()).isNull();
	}

	@Test
	void selfFollowIsRejectedByCheckConstraint() {
		Member member = memberRepository.save(Member.create());

		assertThatThrownBy(() ->
			followRepository.saveAndFlush(Follow.create(member.getId(), member.getId())))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateActiveFollowIsRejectedByPartialUniqueIndex() {
		Member followee = memberRepository.save(Member.create());
		Member follower = memberRepository.save(Member.create());

		followRepository.saveAndFlush(Follow.create(followee.getId(), follower.getId()));

		assertThatThrownBy(() ->
			followRepository.saveAndFlush(Follow.create(followee.getId(), follower.getId())))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
