package com.pinlog.pinlogback.domain.follow;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.integration.PostgresContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
	"management.health.redis.enabled=false",
	"pinlog.auth.stub.enabled=true"
})
@AutoConfigureMockMvc
class FollowApiTests extends PostgresContainerSupport {

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

	private JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
