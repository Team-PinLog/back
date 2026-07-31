package com.pinlog.pinlogback.domain.member;

import static com.pinlog.pinlogback.domain.ai.repository.AiDerivedDataRepository.CANCELLED;
import static com.pinlog.pinlogback.support.AuthTestSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import com.pinlog.pinlogback.domain.auth.service.RefreshTokenStore;
import com.pinlog.pinlogback.domain.collection.entity.Collection;
import com.pinlog.pinlogback.domain.collection.repository.CollectionRepository;
import com.pinlog.pinlogback.domain.member.entity.Member;
import com.pinlog.pinlogback.domain.member.entity.SocialAccount;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;
import com.pinlog.pinlogback.global.security.authentication.MemberPrincipal;
import com.pinlog.pinlogback.global.security.token.AuthCookies;
import com.pinlog.pinlogback.global.security.token.JwtTokenProvider;
import com.pinlog.pinlogback.support.CoreApiFixtures;

import jakarta.servlet.http.Cookie;

/**
 * 회원 탈퇴(API 명세 3.6, 데이터모델 6.9).
 *
 * <p>삭제 표시된 행은 {@code @SQLRestriction} 때문에 리포지토리로 보이지 않는다. 그래서 마스킹과
 * 연쇄 삭제 확인은 모두 {@link JdbcTemplate} native 쿼리로 한다({@code MemberSoftDeleteTests}와
 * 같은 방식).
 *
 * <p>연쇄 대상이 여섯 테이블이라 픽스처를 API로 만든다 — 리포지토리로 직접 넣으면 실제 경로가
 * 만드는 연관(Collection의 record_count, CollectionRecord 링크)을 손으로 재현해야 하고, 그때부터
 * 테스트가 검증하는 것이 "탈퇴가 지우는가"에서 "내가 픽스처를 맞게 만들었는가"로 옮겨간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("회원 탈퇴")
class MemberWithdrawalApiTests extends CoreApiFixtures {

	private static final String PATH = "/v1/me";

	@Autowired
	private CollectionRepository collectionRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JwtTokenProvider tokenProvider;

	/** 폐기 실패 경로만 스텁한다. 다른 테스트에서는 스텁하지 않아 실제 빈에 위임한다. */
	@MockitoSpyBean
	private RefreshTokenStore refreshTokenStore;

	@Test
	@DisplayName("204를 반환하고 member와 social_account를 소프트 삭제한다")
	void withdrawSoftDeletesMemberAndSocialAccount() throws Exception {
		long memberId = newMemberId();
		long accountId = givenSocialAccount(memberId, "google-withdraw-1", "user@example.com");

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(deletedAtOf("core.member", memberId)).isNotNull();
		assertThat(deletedAtOf("core.social_account", accountId)).isNotNull();
	}

	@Test
	@DisplayName("provider_user_id와 email을 마스킹한다 — 원본이 남지 않는다")
	void withdrawMasksPersonalData() throws Exception {
		long memberId = newMemberId();
		long accountId = givenSocialAccount(memberId, "google-withdraw-2", "victim@example.com");

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		// 치환값을 그대로 고정한다. "원본과 다르다"로는 부족하다 — 접두만 붙이는 구현
		// (MASK + email)도 그 단언을 통과하면서 개인정보를 그대로 남긴다. 복구 경로가 없는
		// 파기이므로 형식이 바뀌면 여기서 걸려야 한다.
		Map<String, Object> row = socialAccountRow(accountId);
		assertThat(row.get("provider_user_id")).isEqualTo("withdrawn:" + accountId);
		assertThat(row.get("email")).isEqualTo("withdrawn:" + accountId + "@deleted.invalid");
		// 형식과 별개로 성립해야 하는 성질 — 원본 조각이 어디에도 남지 않는다.
		assertThat(row.values().stream().map(String::valueOf))
			.noneMatch(value -> value.contains("victim") || value.contains("google-withdraw-2"));
	}

	@Test
	@DisplayName("마스킹과 deleted_at이 한 트랜잭션에서 함께 반영된다")
	void maskingAndSoftDeleteLandTogether() throws Exception {
		long memberId = newMemberId();
		long accountId = givenSocialAccount(memberId, "google-withdraw-3", "atomic@example.com");

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		// 둘 중 하나만 적용된 상태가 없다는 것이 계약이다. @SQLDelete + @SQLRestriction 조합에서는
		// 마스킹이 조용히 유실되는 경로가 있어(#34) 같은 행에서 둘을 함께 본다.
		Map<String, Object> row = socialAccountRow(accountId);
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(row.get("provider_user_id")).isEqualTo("withdrawn:" + accountId);
	}

	@Test
	@DisplayName("Record·Context·Collection·CollectionRecord·Follow를 연쇄 소프트 삭제한다")
	void withdrawCascadesToOwnedData() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-4", "cascade@example.com");
		long recordId = createRecord(memberId, "wd-cascade-1", "맥락");
		long contextId = firstContextId(memberId, recordId);
		long collectionId = createCollection(memberId, "내 책장", List.of(recordId));

		long followerId = newMemberId();
		long followId = follow(followerId, collectionId);
		long followeeCollectionId = collectionRepository
			.save(Collection.create(newMemberId(), "남의 책장")).getId();
		long myFollowId = follow(memberId, followeeCollectionId);

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(deletedAtOf("core.record", recordId)).isNotNull();
		assertThat(deletedAtOf("core.context", contextId)).isNotNull();
		assertThat(deletedAtOf("core.collection", collectionId)).isNotNull();
		assertThat(collectionRecordDeletedCount(collectionId)).isEqualTo(1L);
		// 내가 만든 Follow와 나를 대상으로 하는 Follow 둘 다 지운다(06 §6.9).
		assertThat(deletedAtOf("core.follow", myFollowId)).isNotNull();
		assertThat(deletedAtOf("core.follow", followId)).isNotNull();
	}

	/**
	 * 남의 계정을 지우려는 요청이 아니다 — 그런 요청은 만들 수 없다. {@code DELETE /v1/me}에는 경로
	 * 변수가 없고 대상은 항상 인증된 본인이다(BD-14 식별자 은닉). 여기서 보는 것은 <b>내 탈퇴의
	 * 연쇄가 남의 행까지 지우지 않는가</b>이며, 연쇄 조회에서 회원 조건을 빼먹으면 깨진다.
	 */
	@Test
	@DisplayName("내 탈퇴의 연쇄 삭제가 타인 데이터로 번지지 않는다")
	void cascadeDoesNotSpillIntoOtherMembersData() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-5", "mine@example.com");
		createRecord(memberId, "wd-mine-1", "내 맥락");

		long otherId = newMemberId();
		long otherRecordId = createRecord(otherId, "wd-other-1", "남의 맥락");
		long otherCollectionId = createCollection(otherId, "남의 책장", List.of(otherRecordId));

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(deletedAtOf("core.member", otherId)).isNull();
		assertThat(deletedAtOf("core.record", otherRecordId)).isNull();
		assertThat(deletedAtOf("core.collection", otherCollectionId)).isNull();
	}

	@Test
	@DisplayName("AI 파생 데이터를 무효화한다 — State CANCELLED, Embedding is_deleted")
	void withdrawInvalidatesAiDerivedData() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-6", "ai@example.com");
		long recordId = createRecord(memberId, "wd-ai-1", "맥락");
		long contextId = firstContextId(memberId, recordId);
		givenDerivedData(memberId, recordId, contextId, "COMPLETED", "COMPLETED");

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		// COMPLETED도 덮는다. context_keyword에는 is_deleted가 없어 keyword_status가 단독으로
		// 조회 제외를 담당하므로, 남기면 탈퇴한 사용자의 키워드가 계속 노출된다(08 §3.6).
		assertThat(aiState(contextId))
			.containsEntry("embedding_status", CANCELLED)
			.containsEntry("keyword_status", CANCELLED);
		assertThat(embeddingDeleted(contextId)).isTrue();
	}

	@Test
	@DisplayName("파생 데이터가 없는 회원도 탈퇴한다")
	void withdrawWorksWithoutAnyOwnedData() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-7", "bare@example.com");

		// Record·Collection이 없으면 무효화 대상 contextIds가 빈 목록이다. invalidate(List.of())가
		// no-op이어야 하고, 여기서 깨지면 "가입만 하고 아무것도 안 한 회원"이 탈퇴하지 못한다.
		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(deletedAtOf("core.member", memberId)).isNotNull();
	}

	@Test
	@DisplayName("해당 회원의 Refresh를 전부 폐기한다")
	void withdrawRevokesEveryRefreshTokenOfTheMember() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-8", "redis@example.com");
		givenRefreshToken(memberId, "jti-a");
		givenRefreshToken(memberId, "jti-b");

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		assertThat(refreshKeysOf(memberId)).isEmpty();
	}

	/**
	 * 폐기를 트랜잭션 밖(커밋 이후)으로 옮기고 실패를 삼킨 판단의 근거를 고정한다. 삼키지 않으면
	 * <b>이미 커밋된 탈퇴가 500으로 응답하고 컨트롤러에 도달하지 못해 쿠키도 지워지지 않는다</b> —
	 * 사용자는 실패로 보는데 계정은 사라진 상태가 된다(BD-41).
	 *
	 * <p>남은 Refresh는 무해하다. 그것으로 받는 Access는 필터의 탈퇴 판정에 막힌다.
	 */
	@Test
	@DisplayName("Refresh 폐기가 실패해도 탈퇴는 확정되고 쿠키는 지워진다")
	void withdrawSurvivesRefreshRevocationFailure() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-12", "redis-down@example.com");
		doThrow(new RedisConnectionFailureException("Redis 순단을 가장한다"))
			.when(refreshTokenStore).revokeAll(memberId);

		MvcResult result = mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent())
			.andReturn();

		assertThat(deletedAtOf("core.member", memberId)).isNotNull();
		assertThat(expiredCookieNames(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)))
			.containsExactlyInAnyOrder(
				AuthCookies.ACCESS_TOKEN, AuthCookies.REFRESH_TOKEN, AuthCookies.LOGGED_IN);
	}

	@Test
	@DisplayName("인증 쿠키와 표시 쿠키를 모두 만료시킨다")
	void withdrawExpiresAllThreeCookies() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-9", "cookie@example.com");

		MvcResult result = mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent())
			.andReturn();

		List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		assertThat(expiredCookieNames(setCookies)).containsExactlyInAnyOrder(
			AuthCookies.ACCESS_TOKEN, AuthCookies.REFRESH_TOKEN, AuthCookies.LOGGED_IN);
	}

	@Test
	@DisplayName("미인증 요청은 401이다")
	void withdrawWithoutAuthenticationIs401() throws Exception {
		// CSRF 토큰은 준다. CsrfFilter가 인가보다 먼저 돌아서 토큰이 없으면 인증 여부와 무관하게
		// 403이 되고, 그러면 이 테스트가 401을 확인하지 못한다(authentication.md — 403은 CSRF 전용).
		mockMvc.perform(delete(PATH).with(csrf()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("CSRF 토큰이 없으면 403이다")
	void withdrawWithoutCsrfTokenIs403() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-11", "csrf@example.com");

		mockMvc.perform(delete(PATH).with(authentication(principalOf(memberId))))
			.andExpect(status().isForbidden());

		// 거절됐으므로 아무것도 지워지지 않아야 한다.
		assertThat(deletedAtOf("core.member", memberId)).isNull();
	}

	@Test
	@DisplayName("탈퇴한 회원의 Access 쿠키로는 보호 경로에 접근할 수 없다")
	void withdrawnMemberCannotUseARemainingAccessToken() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-withdraw-10", "stale@example.com");

		// 다른 기기에 남은 쿠키. loginAs는 SecurityContext에 직접 넣어 검증 대상인 필터를
		// 건너뛰므로 여기서만 실제 토큰을 쓴다.
		Cookie staleAccess = new Cookie(AuthCookies.ACCESS_TOKEN, tokenProvider.issueAccessToken(memberId));

		mockMvc.perform(get("/v1/records/map").cookie(staleAccess))
			.andExpect(status().isOk());

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		// 같은 쿠키가 이제 401이다. 서명·만료는 그대로이고 탈퇴 여부만 바뀌었다.
		mockMvc.perform(get("/v1/records/map").cookie(staleAccess))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("탈퇴 후 같은 소셜 계정으로 다시 로그인하면 신규 회원이 된다")
	void reSignupAfterWithdrawalCreatesANewMember() throws Exception {
		long memberId = newMemberId();
		givenSocialAccount(memberId, "google-rejoin-1", "rejoin@example.com");

		mockMvc.perform(delete(PATH).with(loginAs(memberId)))
			.andExpect(status().isNoContent());

		// 활성 행이 사라졌으므로 콜백의 기존 회원 판정이 비어 있는 결과를 받는다(08 §3.6).
		// 부분 유니크가 활성행만 대상이라 같은 (provider, provider_user_id)로 저장이 된다.
		Member rejoined = memberRepository.save(Member.create());
		SocialAccount reAccount = socialAccountRepository.saveAndFlush(
			SocialAccount.create(rejoined, SocialProvider.GOOGLE, "google-rejoin-1", "rejoin@example.com"));

		assertThat(rejoined.getId()).isNotEqualTo(memberId);
		assertThat(socialAccountRepository
			.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-rejoin-1"))
			.get()
			.extracting(SocialAccount::getId)
			.isEqualTo(reAccount.getId());
	}

	// --- 픽스처 -------------------------------------------------------------

	/** 이 클래스는 provider를 가리지 않으므로 Google로 고정한다. 공통 픽스처의 4인자 버전에 위임한다. */
	private long givenSocialAccount(long memberId, String providerUserId, String email) {
		return givenSocialAccount(memberId, SocialProvider.GOOGLE, providerUserId, email);
	}

	/** CSRF 없이 인증만 주기 위한 것. {@code loginAs}는 둘을 함께 넣으므로 여기서는 쓸 수 없다. */
	private Authentication principalOf(long memberId) {
		return new UsernamePasswordAuthenticationToken(new MemberPrincipal(memberId), null, List.of());
	}

	private void givenRefreshToken(long memberId, String tokenId) {
		redisTemplate.opsForValue().set("auth:refresh:" + memberId + ":" + tokenId, "1");
		redisTemplate.opsForSet().add("auth:refresh-index:" + memberId, tokenId);
	}

	private void givenDerivedData(long memberId, long recordId, long contextId,
		String embeddingStatus, String keywordStatus) {
		jdbcTemplate.update("""
			INSERT INTO ai.context_ai_state (context_id, embedding_status, keyword_status)
			VALUES (?, ?, ?)
			ON CONFLICT (context_id) DO UPDATE SET
				embedding_status = EXCLUDED.embedding_status,
				keyword_status = EXCLUDED.keyword_status
			""", contextId, embeddingStatus, keywordStatus);
		jdbcTemplate.update("""
			INSERT INTO ai.context_embedding
				(context_id, user_id, record_id, embedding, embedding_profile)
			VALUES (?, ?, ?, ?::vector, 'test-profile')
			""", contextId, memberId, recordId, zeroVector());
	}

	private String zeroVector() {
		return "[" + "0,".repeat(1535) + "0]";
	}

	// --- 검증 (native) ------------------------------------------------------

	private Map<String, Object> socialAccountRow(long id) {
		return jdbcTemplate.queryForMap(
			"SELECT provider_user_id, email, deleted_at FROM core.social_account WHERE id = ?", id);
	}

	private Long collectionRecordDeletedCount(long collectionId) {
		return jdbcTemplate.queryForObject(
			"SELECT count(*) FROM core.collection_record"
				+ " WHERE collection_id = ? AND deleted_at IS NOT NULL",
			Long.class, collectionId);
	}

	private Map<String, Object> aiState(long contextId) {
		return jdbcTemplate.queryForMap(
			"SELECT embedding_status, keyword_status FROM ai.context_ai_state WHERE context_id = ?",
			contextId);
	}

	private boolean embeddingDeleted(long contextId) {
		return jdbcTemplate.queryForObject(
			"SELECT is_deleted FROM ai.context_embedding WHERE context_id = ?", Boolean.class, contextId);
	}

	private Set<String> refreshKeysOf(long memberId) {
		Set<String> tokens = redisTemplate.keys("auth:refresh:" + memberId + ":*");
		Set<String> index = redisTemplate.keys("auth:refresh-index:" + memberId);
		return java.util.stream.Stream.concat(tokens.stream(), index.stream())
			.collect(Collectors.toSet());
	}

	/** Max-Age=0으로 만료된 쿠키 이름만 고른다. 값 비우기만으로는 브라우저에 남는다. */
	private Set<String> expiredCookieNames(List<String> setCookies) {
		return setCookies.stream()
			.filter(header -> header.contains("Max-Age=0"))
			.map(header -> header.substring(0, header.indexOf('=')))
			.collect(Collectors.toSet());
	}

}
