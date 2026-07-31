package com.pinlog.pinlogback.support;

import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.domain.member.repository.MemberRepository;
import com.pinlog.pinlogback.domain.member.repository.SocialAccountRepository;
import com.pinlog.pinlogback.integration.IntegrationContainerSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 여러 도메인의 데이터를 함께 필요로 하는 통합 테스트의 공통 Fixture.
 *
 * <p><b>Core 데이터는 실제 API로 만든다.</b> 리포지토리로 직접 넣으면 실제 경로가 만드는 연관
 * (Collection의 {@code record_count}, CollectionRecord 링크)을 손으로 재현해야 하고, 그때부터
 * 테스트가 검증하는 것이 "기능이 동작하는가"에서 "내가 픽스처를 맞게 만들었는가"로 옮겨간다.
 * {@code FeedFixtures}가 같은 이유로 같은 선택을 했다.
 *
 * <p><b>{@code support}에 둔 이유는 도메인을 가로지르기 때문이다.</b> Record·Collection·Follow를
 * 함께 쓰는 픽스처라 어느 한 도메인 패키지에 두면 그 도메인이 아닌 테스트가 남의 패키지를 참조하게
 * 된다. 한 도메인만 쓰는 픽스처는 계속 그 도메인 안에 둔다({@code FeedFixtures}가 그 경우다).
 *
 * <p>테스트 간 DB를 비우지 않는다(공유 컨테이너). 그래서 단언은 "전체 개수"가 아니라 <b>내가 만든
 * id로 걸러낸 부분</b>에 걸어야 한다 — 다른 테스트 클래스가 만든 행도 같은 테이블에 있다.
 */
public abstract class CoreApiFixtures extends IntegrationContainerSupport {

	protected final JsonMapper jsonMapper = JsonMapper.builder().build();

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected MemberRepository memberRepository;

	@Autowired
	protected SocialAccountRepository socialAccountRepository;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	protected long newMemberId() {
		return memberRepository.save(Member.create()).getId();
	}

	/** @return 만들어진 {@code social_account}의 id */
	protected long givenSocialAccount(
		long memberId, SocialProvider provider, String providerUserId, String email) {
		Member member = memberRepository.findById(memberId).orElseThrow();
		return socialAccountRepository
			.saveAndFlush(SocialAccount.create(member, provider, providerUserId, email))
			.getId();
	}

	protected long createRecord(long memberId, String kakaoPlaceId, String contextBody) throws Exception {
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
			""".formatted(kakaoPlaceId, contextBody);
		JsonNode response = parse(mockMvc.perform(post("/v1/records").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/recordId").asLong();
	}

	protected long firstContextId(long memberId, long recordId) throws Exception {
		JsonNode detail = parse(mockMvc.perform(get("/v1/records/{recordId}", recordId)
				.with(loginAs(memberId)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		return detail.at("/data/contexts/0/contextId").asLong();
	}

	protected long createCollection(long memberId, String title, List<Long> recordIds) throws Exception {
		String ids = recordIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
		JsonNode response = parse(mockMvc.perform(post("/v1/collections").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\": \"" + title + "\", \"recordIds\": [" + ids + "]}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/collectionId").asLong();
	}

	protected long follow(long memberId, long collectionId) throws Exception {
		JsonNode response = parse(mockMvc.perform(post("/v1/follows").with(loginAs(memberId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"collectionId\": " + collectionId + "}"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
		return response.at("/data/followId").asLong();
	}

	/**
	 * 삭제 API가 아니라 UPDATE로 소프트 삭제를 만든다. 삭제 API는 연쇄 삭제까지 수행해 검증 대상이
	 * 함께 사라지므로, "이 행이 삭제되면 어떻게 되는가"를 보려는 테스트에는 쓸 수 없다.
	 */
	protected void softDelete(String table, long id) {
		jdbcTemplate.update("UPDATE " + table + " SET deleted_at = now() WHERE id = ?", id);
	}

	/** 삭제 표시된 행은 {@code @SQLRestriction} 때문에 리포지토리로 보이지 않아 native로 확인한다. */
	protected Object deletedAtOf(String table, long id) {
		return jdbcTemplate.queryForMap("SELECT deleted_at FROM " + table + " WHERE id = ?", id)
			.get("deleted_at");
	}

	protected JsonNode parse(String json) {
		return jsonMapper.readTree(json);
	}
}
