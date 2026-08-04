package com.pinlog.pinlogback.domain.auth.client;

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
 * <p>공급자는 <b>이미 폐기된 토큰에도 {@code 200}</b>을 준다. 재시도가 안전하다는 뜻이다.
 */
@Component
public class NaverUnlinkClient implements SocialUnlinkClient {

	private static final String REVOKE_URI = "https://nid.naver.com/oauth2.0/revoke";

	private final RestClient restClient;
	private final ClientRegistrationRepository clientRegistrations;

	public NaverUnlinkClient(
		@Qualifier("socialUnlinkRestClient") RestClient socialUnlinkRestClient,
		ClientRegistrationRepository clientRegistrations
	) {
		this.restClient = socialUnlinkRestClient;
		this.clientRegistrations = clientRegistrations;
	}

	@Override
	public SocialProvider provider() {
		return SocialProvider.NAVER;
	}

	@Override
	public void unlink(String accessToken) {
		ClientRegistration registration =
			clientRegistrations.findByRegistrationId(SocialProvider.NAVER.registrationId());

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", registration.getClientId());
		form.add("client_secret", registration.getClientSecret());
		form.add("token", accessToken);
		form.add("token_type_hint", "access_token");
		try {
			restClient.post()
				.uri(REVOKE_URI)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientException e) {
			// 본문의 error가 아니라 상태 코드로 판단한다 — 공급자 문서가 그렇게 요구한다.
			throw new SocialUnlinkException("naver revoke failed", e);
		}
	}
}
