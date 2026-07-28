package com.pinlog.pinlogback.domain.follow;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
	"management.health.redis.enabled=false",
	"pinlog.auth.stub.enabled=true"
})
@AutoConfigureMockMvc
class FollowApiTests extends IntegrationContainerSupport {

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private CollectionRepository collectionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void followingShelfViaCollectionReturns201WithNullAlias() throws Exception {
		Member owner = memberRepository.save(Member.create());
		Member me = memberRepository.save(Member.create());
		long collectionId = publishedCollection(owner.getId());

		mockMvc.perform(post("/v1/follows").with(loginAs(me.getId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.followId").isNumber())
			.andExpect(jsonPath("$.data.alias").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.createdAt").exists());
	}

	@Test
	void followingMyOwnShelfIs422() throws Exception {
		Member me = memberRepository.save(Member.create());
		long collectionId = publishedCollection(me.getId());

		mockMvc.perform(post("/v1/follows").with(loginAs(me.getId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.error.code").value("SELF_FOLLOW_NOT_ALLOWED"));
	}

	@Test
	void duplicateFollowIs409WithoutDuplicateRow() throws Exception {
		Member owner = memberRepository.save(Member.create());
		Member me = memberRepository.save(Member.create());
		long collectionId = publishedCollection(owner.getId());
		follow(me.getId(), collectionId);

		mockMvc.perform(post("/v1/follows").with(loginAs(me.getId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_FOLLOW"));

		long rows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.follow WHERE follower_member_id = ? AND deleted_at IS NULL",
			Long.class, me.getId());
		assertThat(rows).isEqualTo(1);
	}

	/**
	 * 중복 확인과 저장 사이에 다른 트랜잭션이 끼어들면 둘 다 "중복 아님"으로 판정한다.
	 * 순차 요청(위 테스트)과 같은 결과 — 한쪽만 201, 나머지는 409 — 로 수렴해야 한다.
	 */
	@Test
	void concurrentDuplicateFollowIs409NotServerError() throws Exception {
		Member owner = memberRepository.save(Member.create());
		Member me = memberRepository.save(Member.create());
		long collectionId = publishedCollection(owner.getId());

		List<RawResponse> responses = runConcurrently(
			() -> postFollow(me.getId(), collectionId),
			() -> postFollow(me.getId(), collectionId));

		assertThat(responses).extracting(RawResponse::status).containsExactlyInAnyOrder(201, 409);
		assertThat(parse(bodyOf(responses, 409)).at("/error/code").asText()).isEqualTo("DUPLICATE_FOLLOW");
		assertThat(activeFollowCount(me.getId())).isEqualTo(1);
	}

	@Test
	void myFollowsArePaginatedByCursor() throws Exception {
		Member me = memberRepository.save(Member.create());
		long followA = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));
		long followB = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));
		long followC = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));

		JsonNode page1 = parse(mockMvc.perform(get("/v1/follows").with(loginAs(me.getId()))
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andReturn().getResponse().getContentAsString());
		assertThat(page1.at("/data/items/0/followId").asLong()).isEqualTo(followC);
		assertThat(page1.at("/data/items/1/followId").asLong()).isEqualTo(followB);

		JsonNode page2 = parse(mockMvc.perform(get("/v1/follows").with(loginAs(me.getId()))
				.param("size", "2").param("cursor", page1.at("/data/nextCursor").asText()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andReturn().getResponse().getContentAsString());
		assertThat(page2.at("/data/items/0/followId").asLong()).isEqualTo(followA);
	}

	@Test
	void followedShelfCollectionsReturnOnlyPublishedActiveOnes() throws Exception {
		Member owner = memberRepository.save(Member.create());
		Member me = memberRepository.save(Member.create());
		long visible = publishedCollection(owner.getId());
		long followId = follow(me.getId(), visible);

		Collection deletedOne = collectionRepository.save(Collection.create(owner.getId(), "삭제될 책"));
		deletedOne.softDelete();
		collectionRepository.saveAndFlush(deletedOne);

		mockMvc.perform(get("/v1/follows/{followId}/collections", followId).with(loginAs(me.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(1))
			.andExpect(jsonPath("$.data.items[0].collectionId").value(visible))
			.andExpect(jsonPath("$.data.items[0].keywords").isEmpty());
	}

	@Test
	void followedShelfCollectionsArePaginatedByCursor() throws Exception {
		Member owner = memberRepository.save(Member.create());
		Member me = memberRepository.save(Member.create());
		long first = publishedCollection(owner.getId());
		long second = publishedCollection(owner.getId());
		long followId = follow(me.getId(), first);

		JsonNode page1 = parse(mockMvc.perform(
				get("/v1/follows/{followId}/collections", followId).with(loginAs(me.getId()))
					.param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andReturn().getResponse().getContentAsString());
		assertThat(page1.at("/data/items/0/collectionId").asLong()).isEqualTo(second);

		JsonNode page2 = parse(mockMvc.perform(
				get("/v1/follows/{followId}/collections", followId).with(loginAs(me.getId()))
					.param("size", "1").param("cursor", page1.at("/data/nextCursor").asText()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andReturn().getResponse().getContentAsString());
		assertThat(page2.at("/data/items/0/collectionId").asLong()).isEqualTo(first);
	}

	@Test
	void withdrawnFolloweeDisappearsFromLibrary() throws Exception {
		Member owner = memberRepository.save(Member.create());
		Member me = memberRepository.save(Member.create());
		long followId = follow(me.getId(), publishedCollection(owner.getId()));

		owner.softDelete();
		memberRepository.saveAndFlush(owner);

		mockMvc.perform(get("/v1/follows").with(loginAs(me.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());

		mockMvc.perform(get("/v1/follows/{followId}/collections", followId).with(loginAs(me.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void aliasIsTrimmedAndBlankBecomesNull() throws Exception {
		Member me = memberRepository.save(Member.create());
		long followId = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));

		mockMvc.perform(patch("/v1/follows/{followId}", followId).with(loginAs(me.getId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"alias\": \"  서울 카페  \"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.alias").value("서울 카페"));

		mockMvc.perform(patch("/v1/follows/{followId}", followId).with(loginAs(me.getId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"alias\": \"   \"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.alias").value(Matchers.nullValue()));

		String stored = jdbcTemplate.queryForObject(
			"SELECT display_name FROM core.follow WHERE id = ?", String.class, followId);
		assertThat(stored).isNull();
	}

	/**
	 * 별칭 수정 요청의 세 형태를 한 테스트로 고정한다(API 명세 8.3, S15P11A705-116).
	 *
	 * <p><b>키 생략({@code {}})과 명시적 null을 구분하지 않는다.</b> 요청 DTO가 컴포넌트 하나짜리
	 * record라 Jackson이 둘을 똑같이 null로 역직렬화하고, 서비스가 그 null을 "제거"로 해석한다.
	 * 명세 8.3은 명시적 null만 제거로 정의하고 키 생략은 정의하지 않았는데, 구분하지 않기로 정했다 —
	 * Follow에서 수정 가능한 필드가 alias 하나뿐이라 부분 수정 요청이 나올 이유가 없다.
	 *
	 * <p>정의되지 않은 입력이 사용자 데이터를 지우는 상태로 두지 않으려고 계약으로 못 박는다.
	 * 셋 중 하나라도 동작이 바뀌면 이 테스트가 실패한다.
	 */
	@Test
	void aliasSetRemoveAndKeyOmissionAllBehaveAsContracted() throws Exception {
		Member me = memberRepository.save(Member.create());
		long followId = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));

		patchAlias(me.getId(), followId, "{\"alias\": \"취향 좋은 카페\"}")
			.andExpect(jsonPath("$.data.alias").value("취향 좋은 카페"));
		assertThat(storedAlias(followId)).isEqualTo("취향 좋은 카페");

		patchAlias(me.getId(), followId, "{\"alias\": null}")
			.andExpect(jsonPath("$.data.alias").value(Matchers.nullValue()));
		assertThat(storedAlias(followId)).isNull();

		patchAlias(me.getId(), followId, "{\"alias\": \"다시 설정\"}")
			.andExpect(jsonPath("$.data.alias").value("다시 설정"));

		// 키 자체가 없는 요청. 명시적 null과 같은 결과여야 한다.
		patchAlias(me.getId(), followId, "{}")
			.andExpect(jsonPath("$.data.alias").value(Matchers.nullValue()));
		assertThat(storedAlias(followId)).isNull();
	}

	@Test
	void aliasLongerThan20CharsIs400() throws Exception {
		Member me = memberRepository.save(Member.create());
		long followId = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));

		mockMvc.perform(patch("/v1/follows/{followId}", followId).with(loginAs(me.getId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"alias\": \"" + "가".repeat(21) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
	}

	@Test
	void sameAliasIsAllowedOnDifferentFollows() throws Exception {
		Member me = memberRepository.save(Member.create());
		long followA = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));
		long followB = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));

		for (long followId : new long[] {followA, followB}) {
			mockMvc.perform(patch("/v1/follows/{followId}", followId).with(loginAs(me.getId()))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"alias\": \"같은 별칭\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.alias").value("같은 별칭"));
		}
	}

	@Test
	void unfollowReturns204AndDisappearsFromList() throws Exception {
		Member me = memberRepository.save(Member.create());
		long followId = follow(me.getId(), publishedCollection(memberRepository.save(Member.create()).getId()));

		mockMvc.perform(delete("/v1/follows/{followId}", followId).with(loginAs(me.getId())))
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1/follows").with(loginAs(me.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items").isEmpty());
	}

	@Test
	void someoneElsesFollowIdIsHiddenAs404() throws Exception {
		Member me = memberRepository.save(Member.create());
		Member other = memberRepository.save(Member.create());
		long othersFollow = follow(other.getId(),
			publishedCollection(memberRepository.save(Member.create()).getId()));

		mockMvc.perform(get("/v1/follows/{followId}/collections", othersFollow).with(loginAs(me.getId())))
			.andExpect(status().isNotFound());

		mockMvc.perform(delete("/v1/follows/{followId}", othersFollow).with(loginAs(me.getId())))
			.andExpect(status().isNotFound());
	}

	private long publishedCollection(long ownerId) {
		return collectionRepository.save(Collection.create(ownerId, "책장")).getId();
	}

	private long follow(long memberId, long collectionId) throws Exception {
		JsonNode response = parse(mockMvc.perform(post("/v1/follows").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/followId").asLong();
	}

	private ResultActions patchAlias(long memberId, long followId, String body) throws Exception {
		return mockMvc.perform(patch("/v1/follows/{followId}", followId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk());
	}

	private String storedAlias(long followId) {
		return jdbcTemplate.queryForObject(
			"SELECT display_name FROM core.follow WHERE id = ?", String.class, followId);
	}

	private record RawResponse(int status, String body) {
	}

	private RawResponse postFollow(long memberId, long collectionId) throws Exception {
		MockHttpServletResponse response = mockMvc.perform(post("/v1/follows").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andReturn().getResponse();
		return new RawResponse(response.getStatus(), response.getContentAsString());
	}

	/**
	 * 두 요청을 같은 순간에 출발시킨다. 어느 쪽이 이기는지는 보장하지 않으므로 단정은 순서에
	 * 의존하지 않는다.
	 */
	private List<RawResponse> runConcurrently(Callable<RawResponse> first, Callable<RawResponse> second)
		throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<RawResponse>> futures = List.of(first, second).stream()
				.map(call -> executor.submit(() -> {
					ready.countDown();
					start.await();
					return call.call();
				}))
				.toList();
			ready.await();
			start.countDown();
			return List.of(futures.get(0).get(), futures.get(1).get());
		} finally {
			executor.shutdownNow();
		}
	}

	private String bodyOf(List<RawResponse> responses, int status) {
		return responses.stream()
			.filter(response -> response.status() == status)
			.map(RawResponse::body)
			.findFirst()
			.orElseThrow();
	}

	private long activeFollowCount(long followerMemberId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.follow WHERE follower_member_id = ? AND deleted_at IS NULL",
			Long.class, followerMemberId);
	}

	private JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
