package com.pinlog.pinlogback.domain.collection;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.record.repository.RecordRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 접근 권한표(API 명세 12장)와 공개 범위 규약(데이터모델 5장)의 행별 검증.
 * 타인에게 Context 원문·신원 정보가 어떤 형태로도 나가지 않는 것이 이 클래스의 존재 이유다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicCollectionApiTests extends IntegrationContainerSupport {

	private static final String SECRET_CONTEXT_BODY = "아무도 몰래 저장한 이유";

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private RecordRepository recordRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void strangerSeesPublicCollectionWithoutContextBodies() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long collectionId = publicCollectionOf(owner, "pub-detail-1");

		String payload = mockMvc.perform(get("/v1/collections/{id}", collectionId)
				.with(loginAs(stranger)).param("recordSize", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ownedByMe").value(false))
			.andExpect(jsonPath("$.data.records.items[0].contexts").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.records.items[0].place.name").exists())
			.andExpect(jsonPath("$.data.records.items[0].keywords").isEmpty())
			.andExpect(jsonPath("$.data.records.items[0].createdAt").exists())
			.andExpect(jsonPath("$.data.records.items[0].addedToCollectionAt").exists())
			.andReturn().getResponse().getContentAsString();

		assertThat(payload).doesNotContain(SECRET_CONTEXT_BODY);
	}

	@Test
	void publicResponseNeverContainsIdentityFields() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long collectionId = publicCollectionOf(owner, "pub-identity-1");

		String payload = mockMvc.perform(get("/v1/collections/{id}", collectionId)
				.with(loginAs(stranger)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();

		assertThat(payload).doesNotContain("memberId");
		assertThat(payload).doesNotContain("email");
		assertThat(payload).doesNotContain("nickname");
		assertThat(payload).doesNotContain("socialAccount");
	}

	@Test
	void followerSeesOwnAliasOnlyAndFollowStatus() throws Exception {
		long owner = newMemberId();
		long followerA = newMemberId();
		long followerB = newMemberId();
		long collectionId = publicCollectionOf(owner, "pub-alias-1");
		long followA = follow(followerA, collectionId);
		setAlias(followerA, followA, "A의 별칭");
		follow(followerB, collectionId);

		mockMvc.perform(get("/v1/collections/{id}", collectionId).with(loginAs(followerA)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.follow.followed").value(true))
			.andExpect(jsonPath("$.data.follow.followId").value(followA))
			.andExpect(jsonPath("$.data.follow.alias").value("A의 별칭"));

		String payloadOfB = mockMvc.perform(get("/v1/collections/{id}", collectionId)
				.with(loginAs(followerB)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.follow.followed").value(true))
			.andExpect(jsonPath("$.data.follow.alias").value(Matchers.nullValue()))
			.andReturn().getResponse().getContentAsString();

		assertThat(payloadOfB).doesNotContain("A의 별칭");
	}

	@Test
	void nonFollowerGetsFollowedFalse() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long collectionId = publicCollectionOf(owner, "pub-nofollow-1");

		mockMvc.perform(get("/v1/collections/{id}", collectionId).with(loginAs(stranger)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.follow.followed").value(false))
			.andExpect(jsonPath("$.data.follow.followId").value(Matchers.nullValue()));
	}

	@Test
	void ownerStillSeesContextsAndNullFollow() throws Exception {
		long owner = newMemberId();
		long collectionId = publicCollectionOf(owner, "pub-owner-1");

		mockMvc.perform(get("/v1/collections/{id}", collectionId).with(loginAs(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.ownedByMe").value(true))
			.andExpect(jsonPath("$.data.follow").value(Matchers.nullValue()))
			.andExpect(jsonPath("$.data.records.items[0].contexts[0].body").value(SECRET_CONTEXT_BODY));
	}

	@Test
	void withdrawnOwnersCollectionIsHiddenFromOthers() throws Exception {
		Member owner = memberRepository.save(Member.create());
		long stranger = newMemberId();
		long collectionId = publicCollectionOf(owner.getId(), "pub-withdrawn-1");

		owner.softDelete();
		memberRepository.saveAndFlush(owner);

		mockMvc.perform(get("/v1/collections/{id}", collectionId).with(loginAs(stranger)))
			.andExpect(status().isNotFound());
	}

	@Test
	void unpublishedCollectionIsHiddenFromOthers() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long collectionId = publicCollectionOf(owner, "pub-unpub-1");
		jdbcTemplate.update("UPDATE core.collection SET is_published = false WHERE id = ?", collectionId);

		mockMvc.perform(get("/v1/collections/{id}", collectionId).with(loginAs(stranger)))
			.andExpect(status().isNotFound());
	}

	@Test
	void softDeletedRecordIsExcludedFromPublicView() throws Exception {
		long owner = newMemberId();
		long stranger = newMemberId();
		long recordA = createRecord(owner, "pub-del-1a");
		long recordB = createRecord(owner, "pub-del-1b");
		long collectionId = createCollection(owner, "일부 삭제", List.of(recordA, recordB));

		recordRepository.findById(recordA).ifPresent(record -> {
			record.softDelete();
			recordRepository.saveAndFlush(record);
		});

		JsonNode response = parse(mockMvc.perform(get("/v1/collections/{id}", collectionId)
				.with(loginAs(stranger)).param("recordSize", "10"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());

		List<Long> recordIds = new java.util.ArrayList<>();
		response.at("/data/records/items")
			.forEach(item -> recordIds.add(item.at("/recordId").asLong()));
		assertThat(recordIds).containsExactly(recordB);
	}

	@Test
	void followersCannotModifyCollectionOrContexts() throws Exception {
		long owner = newMemberId();
		long follower = newMemberId();
		long recordId = createRecord(owner, "pub-modify-1");
		long collectionId = createCollection(owner, "남의 책", List.of(recordId));
		follow(follower, collectionId);
		long contextId = ownerFirstContextId(owner, recordId);

		mockMvc.perform(patch("/v1/collections/{id}", collectionId).with(loginAs(follower))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"탈취\"}"))
			.andExpect(status().isNotFound());

		mockMvc.perform(post("/v1/collections/{id}/records", collectionId).with(loginAs(follower))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"recordIds\": [" + recordId + "]}"))
			.andExpect(status().isNotFound());

		mockMvc.perform(patch("/v1/records/{recordId}/contexts/{contextId}", recordId, contextId)
				.with(loginAs(follower))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\": \"남의 맥락 수정\"}"))
			.andExpect(status().isNotFound());

		mockMvc.perform(get("/v1/records/{recordId}", recordId).with(loginAs(follower)))
			.andExpect(status().isNotFound());
	}

	private long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	private long publicCollectionOf(long ownerId, String kakaoPlaceId) throws Exception {
		long recordId = createRecord(ownerId, kakaoPlaceId);
		return createCollection(ownerId, "공개 책", List.of(recordId));
	}

	private long createRecord(long memberId, String kakaoPlaceId) throws Exception {
		String body = """
			{
			\t"place": {
			\t\t"kakaoPlaceId": "%s",
			\t\t"name": "장소",
			\t\t"address": "주소",
			\t\t"lat": 37.5,
			\t\t"lng": 127.0
			\t},
			\t"contextBody": "%s"
			}
			""".formatted(kakaoPlaceId, SECRET_CONTEXT_BODY);
		JsonNode response = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/recordId").asLong();
	}

	private long createCollection(long memberId, String title, List<Long> recordIds) throws Exception {
		String ids = recordIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		JsonNode response = parse(mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"" + title + "\", \"recordIds\": [" + ids + "]}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/collectionId").asLong();
	}

	private long follow(long memberId, long collectionId) throws Exception {
		JsonNode response = parse(mockMvc.perform(post("/v1/follows").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/followId").asLong();
	}

	private void setAlias(long memberId, long followId, String alias) throws Exception {
		mockMvc.perform(patch("/v1/follows/{followId}", followId).with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"alias\": \"" + alias + "\"}"))
			.andExpect(status().isOk());
	}

	private long ownerFirstContextId(long ownerId, long recordId) throws Exception {
		JsonNode detail = parse(mockMvc.perform(get("/v1/records/{recordId}", recordId)
				.with(loginAs(ownerId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		return detail.at("/data/contexts/0/contextId").asLong();
	}

	private JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
