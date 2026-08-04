package com.pinlog.pinlogback.domain.auth.client;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Naver 연결 해제. {@code POST https://nid.naver.com/oauth2.0/revoke}.
 *
 * <p>RFC 7009형 토큰 폐기라 <b>클라이언트 인증을 요구한다</b> — {@code client_id}·
 * {@code client_secret}이 빠지면 {@code 401 unauthorized_client}다. 값은 로그인이 쓰는 등록정보에서
 * 그대로 가져온다. 별도 설정 키를 두면 로그인과 해제가 다른 앱을 가리키는 상태가 만들어진다.
 *
 * <p>{@code token_type_hint=access_token}을 명시한다. 기본값과 같지만, 공급자가 연결된
 * refresh token까지 함께 폐기(cascade)한다는 사실이 이 값에 달려 있어 의도를 드러내 둔다.
 *
 * <p><b>⚠ 엔드포인트가 확정되지 않았다.</b> 이 상수는 사내에 공유된 「§4.3 Token Revocation」
 * 문서를 근거로 삼았는데, 리뷰에서 Naver의 연동 해제는 토큰 엔드포인트에
 * {@code grant_type=delete}·{@code service_provider=NAVER}를 보내는 방식이라는 지적이 있었고
 * 독립 자료들도 그쪽을 가리킨다. 공식 문서 페이지를 직접 확인하지 못해 <b>스테이징에서 Naver를
 * 가장 먼저, 실패 케이스까지 태워 확정해야 한다</b>(back#181 리뷰 ⓐ).
 *
 * <p>어느 쪽이든 <b>응답 판정은 본문까지 본다</b> — 아래 {@code requireNoErrorInBody}. 상태 코드만
 * 보면 200 본문에 {@code error}를 담아 주는 형태에서 해제 실패가 성공으로 읽힌다.
 */
@Component
public class NaverUnlinkClient implements SocialUnlinkClient {

	private static final String REVOKE_URI = "https://nid.naver.com/oauth2.0/revoke";

	private final RestClient restClient;
	private final ClientRegistrationRepository clientRegistrations;
	private final ObjectMapper objectMapper;

	public NaverUnlinkClient(
		@Qualifier("socialUnlinkRestClient") RestClient socialUnlinkRestClient,
		ClientRegistrationRepository clientRegistrations,
		ObjectMapper objectMapper
	) {
		this.restClient = socialUnlinkRestClient;
		this.clientRegistrations = clientRegistrations;
		this.objectMapper = objectMapper;
	}

	@Override
	public SocialProvider provider() {
		return SocialProvider.NAVER;
	}

	@Override
	public void unlink(String accessToken) {
		// 등록정보가 없으면 설정이 빠진 것이다. 그대로 역참조하면 NPE가 WITHDRAWAL_FAILED로
		// 뭉개져 "공급자 장애"와 구분되지 않는다. 되풀이해도 같으므로 재시도 대상도 아니다.
		ClientRegistration registration =
			clientRegistrations.findByRegistrationId(SocialProvider.NAVER.registrationId());
		if (registration == null) {
			throw new SocialUnlinkException(
				"naver client registration is missing", null, false);
		}

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", registration.getClientId());
		form.add("client_secret", registration.getClientSecret());
		form.add("token", accessToken);
		form.add("token_type_hint", "access_token");
		String body;
		try {
			body = restClient.post()
				.uri(REVOKE_URI)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(String.class);
		} catch (RestClientException e) {
			throw SocialUnlinkException.from("naver revoke failed", e);
		}
		requireNoErrorInBody(body);
	}

	/**
	 * <b>상태 코드만 보지 않는다.</b> Naver의 연동 해제 계열 API는 실패를 {@code 200} 본문의
	 * {@code error} 필드로 알리는 경우가 보고돼 있다. 상태 코드만 보면 그때 <b>해제 실패가 성공으로
	 * 읽히고 회원이 지워진다</b> — 마스킹으로 {@code provider_user_id}가 파기되므로 그 뒤엔 영구히
	 * 못 끊는다. 이 PR이 존재하는 이유인 바로 그 상태를 Naver 경로에서만 만들게 된다.
	 *
	 * <p>그래서 <b>본문에 {@code error}가 있으면 2xx여도 실패로 본다.</b> 판정을 이렇게 두면
	 * 응답 형태가 어느 쪽이든 안전하다 — 본문 없는 성공도, {@code result: success}를 주는 성공도
	 * 통과하고, {@code error}를 담은 실패만 걸린다.
	 *
	 * <p>본문을 못 읽는 경우는 실패로 보지 않는다. 해제가 됐는지 안 됐는지 모르는 상태에서
	 * 지우지 않는 쪽이 안전하지만, 여기서는 파싱 실패가 곧 "형식이 예상과 다르다"이므로 그것도
	 * 확인되지 않은 해제다 — 실패로 올린다.
	 */
	private void requireNoErrorInBody(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return;
		}
		try {
			JsonNode parsed = objectMapper.readTree(body);
			if (parsed.has("error")) {
				throw new SocialUnlinkException(
					"naver revoke returned an error in the body: " + parsed.path("error").asString(),
					null, false);
			}
		} catch (JacksonException e) {
			throw new SocialUnlinkException("naver revoke response was not readable", e, false);
		}
	}
}
