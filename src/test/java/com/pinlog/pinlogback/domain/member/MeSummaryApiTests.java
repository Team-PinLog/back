package com.pinlog.pinlogback.domain.member;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.support.CoreApiFixtures;

/**
 * 마이페이지 요약(API 명세 3.5).
 *
 * <p>이 테스트가 실증해야 하는 것이 하나 있다 — <b>{@code @SQLRestriction}이 count 쿼리에도
 * 적용되는가.</b> {@code MemberRepository.isActive}의 javadoc이 그 질문을 별건으로 남겨 뒀고
 * (S15P11A705-147), 활성 기준 집계 네 개가 필요한 이 티켓에서 처음 답이 필요해졌다. 그래서
 * 모든 카운트 검증에 <b>소프트 삭제된 행을 함께 두고</b> 센다 — 구현이 파생 쿼리든 명시
 * {@code @Query}든 이 테스트가 결과로 가른다.
 *
 * <p>소프트 삭제는 삭제 API가 아니라 {@code JdbcTemplate}으로 만든다. 삭제 API는 연쇄 삭제까지
 * 수행해서 세려는 대상이 함께 사라지고, 그러면 검증하는 것이 "카운트가 활성만 세는가"에서
 * "연쇄 삭제가 무엇을 지웠는가"로 옮겨간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("마이페이지 요약")
class MeSummaryApiTests extends CoreApiFixtures {

	private static final String PATH = "/v1/me/summary";

	@Autowired
	private CollectionRepository collectionRepository;

	@Test
	@DisplayName("계정 정보와 네 카운트를 명세 형태로 반환한다")
	void returnsAccountInfoAndFourCounts() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.KAKAO, "kakao-summary-1", "summary@kakao.com");
		long recordId = createRecord(memberId, "summary-place-1", "맥락");
		// 내 Collection 둘 — API로 만든 것 하나와, 남이 팔로우할 대상 하나.
		createCollection(memberId, "내 책장", List.of(recordId));
		long myShelfToBeFollowed = collectionRepository.save(Collection.create(memberId, "팔로우될 책장")).getId();
		// 남의 Collection — 내가 팔로우하지만 내 collectionCount에는 들어가지 않는다.
		long othersShelf = collectionRepository.save(Collection.create(newMemberId(), "남의 책장")).getId();

		follow(newMemberId(), myShelfToBeFollowed);
		follow(memberId, othersShelf);

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.provider").value("KAKAO"))
			.andExpect(jsonPath("$.data.email").value("summary@kakao.com"))
			.andExpect(jsonPath("$.data.recordCount").value(1))
			.andExpect(jsonPath("$.data.collectionCount").value(2))
			.andExpect(jsonPath("$.data.followerCount").value(1))
			.andExpect(jsonPath("$.data.followingCount").value(1));
	}

	@Test
	@DisplayName("소프트 삭제된 Record·Collection은 카운트에서 빠진다")
	void softDeletedRecordsAndCollectionsAreExcluded() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-summary-2", "summary@gmail.com");
		long liveRecordId = createRecord(memberId, "summary-live-1", "살아남을 맥락");
		long deadRecordId = createRecord(memberId, "summary-dead-1", "지워질 맥락");
		long liveCollectionId = createCollection(memberId, "살아남을 책장", List.of(liveRecordId));
		long deadCollectionId = createCollection(memberId, "지워질 책장", List.of(liveRecordId));

		softDelete("core.record", deadRecordId);
		softDelete("core.collection", deadCollectionId);

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recordCount").value(1))
			.andExpect(jsonPath("$.data.collectionCount").value(1));

		// 지워지지 않은 쪽이 남아 있는 것까지 확인한다 — 카운트가 0이 되어 통과하는 것을 막는다.
		assertThat(deletedAtOf("core.record", liveRecordId)).isNull();
		assertThat(deletedAtOf("core.collection", liveCollectionId)).isNull();
	}

	@Test
	@DisplayName("소프트 삭제된 Follow는 양쪽 카운트에서 빠진다")
	void softDeletedFollowsAreExcluded() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.NAVER, "naver-summary-3", "summary@naver.com");
		long myShelf = collectionRepository.save(Collection.create(memberId, "내 책장")).getId();

		long stayingFollowerId = newMemberId();
		long leavingFollowerId = newMemberId();
		follow(stayingFollowerId, myShelf);
		long leavingFollowId = follow(leavingFollowerId, myShelf);

		long stayingShelfId = collectionRepository.save(Collection.create(newMemberId(), "계속 볼 책장")).getId();
		long leavingShelfId = collectionRepository.save(Collection.create(newMemberId(), "끊을 책장")).getId();
		follow(memberId, stayingShelfId);
		long myLeavingFollowId = follow(memberId, leavingShelfId);

		softDelete("core.follow", leavingFollowId);
		softDelete("core.follow", myLeavingFollowId);

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.followerCount").value(1))
			.andExpect(jsonPath("$.data.followingCount").value(1));
	}

	/**
	 * 팔로우 단위가 Collection이 아니라 <b>작성자</b>라는 것을 고정한다. {@code uq_follow_active}가
	 * {@code (followee_member_id, follower_member_id)} 활성 유니크이므로(V3:125) 같은 작성자의 다른
	 * Collection을 팔로우하려는 요청은 409로 거절되고, 팔로워 수는 1을 유지한다.
	 */
	@Test
	@DisplayName("같은 작성자의 다른 Collection 팔로우는 거절되고 팔로워 수는 1이다")
	void followerCountCountsTheFollowerOncePerOwner() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.KAKAO, "kakao-summary-4", "summary4@kakao.com");
		long firstShelf = collectionRepository.save(Collection.create(memberId, "첫 책장")).getId();
		long secondShelf = collectionRepository.save(Collection.create(memberId, "둘째 책장")).getId();

		long followerId = newMemberId();
		follow(followerId, firstShelf);

		mockMvc.perform(post("/v1/follows").with(loginAs(followerId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + secondShelf + "}"))
			.andExpect(status().isConflict());

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.followerCount").value(1));
	}

	@Test
	@DisplayName("데이터가 없는 회원은 카운트가 모두 0이다")
	void freshMemberHasZeroCounts() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-summary-5", "fresh@gmail.com");

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recordCount").value(0))
			.andExpect(jsonPath("$.data.collectionCount").value(0))
			.andExpect(jsonPath("$.data.followerCount").value(0))
			.andExpect(jsonPath("$.data.followingCount").value(0));
	}

	@Test
	@DisplayName("memberId를 반환하지 않는다")
	void doesNotExposeMemberId() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.KAKAO, "kakao-summary-6", "summary6@kakao.com");

		// 개인 API는 서버가 쿠키로 사용자를 식별한다. 자신의 내부 ID를 알 필요가 없다(08 §1.1, BD-14).
		String body = mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(jsonMapper.readTree(body).at("/data").has("memberId")).isFalse();
		assertThat(body).doesNotContain(String.valueOf(memberId));
	}

	@Test
	@DisplayName("타인의 데이터는 내 카운트에 들어오지 않는다")
	void countsOnlyMyOwnData() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, SocialProvider.GOOGLE, "google-summary-7", "mine7@gmail.com");
		long otherId = newMemberId();
		long otherRecordId = createRecord(otherId, "summary-other-1", "남의 맥락");
		createCollection(otherId, "남의 책장", List.of(otherRecordId));

		mockMvc.perform(get(PATH).with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.recordCount").value(0))
			.andExpect(jsonPath("$.data.collectionCount").value(0));
	}

	@Test
	@DisplayName("미인증 요청은 401이다")
	void unauthenticatedRequestIs401() throws Exception {
		mockMvc.perform(get(PATH).with(csrf()))
			.andExpect(status().isUnauthorized());
	}

}
