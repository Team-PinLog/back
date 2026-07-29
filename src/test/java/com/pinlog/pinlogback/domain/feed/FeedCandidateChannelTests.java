package com.pinlog.pinlogback.domain.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.feed.repository.FeedCandidateRepository;
import com.pinlog.pinlogback.domain.feed.repository.FeedCollectionCard;
import com.pinlog.pinlogback.domain.feed.service.FeedCandidate;
import com.pinlog.pinlogback.domain.member.entity.Member;

/**
 * 후보 채널 쿼리의 노출 조건(feed-tests 4장 C1~C9).
 *
 * <p>API가 아니라 리포지토리를 직접 부른다. 채널 쿼리의 WHERE 절이 검증 대상인데, API 응답까지
 * 거치면 다양성 조정·페이지 크기에 가려 <b>무엇 때문에 빠졌는지</b>가 흐려지기 때문이다.
 *
 * <p>공유 DB이므로 채널 결과 전체가 아니라 <b>내가 만든 id의 포함 여부</b>만 본다. limit을 넉넉히
 * 줘서 "최신 100건 밖으로 밀려나 안 보이는 것"과 "조건에 걸려 빠진 것"이 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedCandidateChannelTests extends FeedFixtures {

	private static final int GENEROUS_LIMIT = 5_000;

	@Autowired
	private FeedCandidateRepository candidateRepository;

	@Autowired
	private CollectionRepository collectionRepository;

	/** C1 — 본인 소유 Collection은 모든 채널에서 빠진다. 타인에게는 보인다. */
	@Test
	void ownCollectionIsExcludedFromEveryChannel() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long mine = publishedCollection(owner, uniqueSeed("own"));

		assertThat(recentIds(owner)).doesNotContain(mine);
		assertThat(recentIds(stranger)).contains(mine);
		assertThat(randomIds(owner)).doesNotContain(mine);
	}

	/** C2 — 소프트 삭제된 Collection은 후보가 아니다. */
	@Test
	void softDeletedCollectionIsExcluded() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		long collectionId = publishedCollection(owner, uniqueSeed("deleted"));
		assertThat(recentIds(viewer)).contains(collectionId);

		collectionRepository.findById(collectionId).ifPresent(collection -> {
			collection.softDelete();
			collectionRepository.saveAndFlush(collection);
		});

		assertThat(recentIds(viewer)).doesNotContain(collectionId);
	}

	/** C3 — 비공개로 전환된 Collection은 후보가 아니다. */
	@Test
	void unpublishedCollectionIsExcluded() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		long collectionId = publishedCollection(owner, uniqueSeed("unpublished"));

		jdbcTemplate.update("UPDATE core.collection SET is_published = false WHERE id = ?", collectionId);

		assertThat(recentIds(viewer)).doesNotContain(collectionId);
	}

	/** C4 — 탈퇴한 User의 Collection은 후보가 아니다. */
	@Test
	void withdrawnOwnersCollectionIsExcluded() throws Exception {
		Member owner = memberRepository.save(Member.create());
		long viewer = newMemberId();
		long collectionId = publishedCollection(owner.getId(), uniqueSeed("withdrawn"));

		owner.softDelete();
		memberRepository.saveAndFlush(owner);

		assertThat(recentIds(viewer)).doesNotContain(collectionId);
	}

	/** C5 — 담긴 Record가 없는 Collection은 후보가 아니다. */
	@Test
	void emptyCollectionIsExcluded() throws Exception {
		long owner = newMemberId();
		long viewer = newMemberId();
		long collectionId = publishedCollection(owner, uniqueSeed("empty"));

		jdbcTemplate.update("UPDATE core.collection SET record_count = 0 WHERE id = ?", collectionId);

		assertThat(recentIds(viewer)).doesNotContain(collectionId);
	}

	/** C6 — 팔로우 채널에서 나온 후보는 출처가 보존된다. 그 값이 점수 공식의 followSignal이다. */
	@Test
	void followChannelPreservesItsOrigin() throws Exception {
		long owner = newMemberId();
		long follower = newMemberId();
		long collectionId = publishedCollection(owner, uniqueSeed("followed"));
		follow(follower, collectionId);

		List<FeedCandidate> followed = candidateRepository.findFollowed(follower, GENEROUS_LIMIT);

		assertThat(followed).isNotEmpty();
		assertThat(followed).allMatch(FeedCandidate::fromFollow);
		assertThat(followed.stream().map(FeedCandidate::collectionId)).contains(collectionId);
	}

	/** C8 — 팔로우가 0건인 사용자는 팔로우 채널이 비고, 나머지 채널로 후보가 구성된다. */
	@Test
	void memberWithoutFollowsGetsEmptyFollowChannel() throws Exception {
		long viewer = newMemberId();
		publishedCollection(newMemberId(), uniqueSeed("no-follow"));

		assertThat(candidateRepository.findFollowed(viewer, GENEROUS_LIMIT)).isEmpty();
		assertThat(candidateRepository.findRecent(viewer, GENEROUS_LIMIT)).isNotEmpty();
	}

	/** C9 — 요청한 수보다 Collection이 적어도 있는 만큼 돌려주고 예외를 내지 않는다. */
	@Test
	void fewerCollectionsThanRequestedIsNotAnError() throws Exception {
		long viewer = newMemberId();

		assertThat(candidateRepository.findRandomSample(viewer, GENEROUS_LIMIT, 12_345L))
			.doesNotHaveDuplicates();
	}

	/** 같은 seed는 같은 표본을 만든다 — 커서로 이어받은 페이지가 같은 후보 풀을 복원하는 근거다. */
	@Test
	void randomSampleIsStableForTheSameSeed() throws Exception {
		long viewer = newMemberId();
		publishedCollection(newMemberId(), uniqueSeed("sample"));

		assertThat(randomIds(viewer)).isEqualTo(randomIds(viewer));
	}

	/** 재검증은 입력 순서를 보존하고, 조건에 걸린 id는 조용히 뺀다. */
	@Test
	void verificationPreservesOrderAndDropsIneligible() throws Exception {
		long owner = newMemberId();
		long first = publishedCollection(owner, uniqueSeed("verify-1"));
		long second = publishedCollection(owner, uniqueSeed("verify-2"));
		long hidden = publishedCollection(owner, uniqueSeed("verify-3"));
		jdbcTemplate.update("UPDATE core.collection SET is_published = false WHERE id = ?", hidden);

		List<FeedCollectionCard> cards = candidateRepository.findVerifiedCards(
			List.of(second, hidden, first));

		assertThat(cards.stream().map(FeedCollectionCard::collectionId))
			.containsExactly(second, first);
		assertThat(cards.get(0).recordCount()).isPositive();
	}

	@Test
	void verificationOfEmptyInputDoesNotQuery() {
		assertThat(candidateRepository.findVerifiedCards(List.of())).isEmpty();
		assertThat(candidateRepository.findExistingIds(List.of())).isEmpty();
	}

	private List<Long> recentIds(long memberId) {
		return candidateRepository.findRecent(memberId, GENEROUS_LIMIT).stream()
			.map(FeedCandidate::collectionId)
			.toList();
	}

	private List<Long> randomIds(long memberId) {
		return candidateRepository.findRandomSample(memberId, 50, 987_654L).stream()
			.map(FeedCandidate::collectionId)
			.toList();
	}
}
