package com.pinlog.pinlogback.domain.auth.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * Google 연결 해제. {@code POST https://oauth2.googleapis.com/revoke}.
 *
 * <p>클라이언트 인증이 없다 — 토큰 자체가 자격이다. 성공은 {@code 200}이고, 이미 폐기된 토큰이나
 * 형식이 어긋난 요청은 {@code 400}이다.
 */
@Component
public class GoogleUnlinkClient implements SocialUnlinkClient {

	private static final String REVOKE_URI = "https://oauth2.googleapis.com/revoke";

	private final RestClient restClient;

	public GoogleUnlinkClient(@Qualifier("socialUnlinkRestClient") RestClient socialUnlinkRestClient) {
		this.restClient = socialUnlinkRestClient;
	}

	@Override
	public SocialProvider provider() {
		return SocialProvider.GOOGLE;
	}

	@Override
	public void unlink(ProviderTokens tokens) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		// refresh token이 있으면 그것을 폐기한다. access token을 보내면 200이 오지만 그것은
		// 토큰이 폐기된 것이고, 계정의 「서드파티 앱 및 서비스」에는 앱이 그대로 남는다(실측).
		// 폐기의 연쇄가 access → refresh 방향이라 승인은 refresh token 쪽에 달려 있다.
		//
		// 없으면 access token으로 떨어뜨린다 — 승인까지 지우지는 못하지만 토큰은 죽는다.
		// 인가 요청에 access_type=offline·prompt=consent가 빠지면 여기로 온다.
		String token = tokens.refreshToken() != null ? tokens.refreshToken() : tokens.accessToken();
		form.add("token", token);
		try {
			restClient.post()
				.uri(REVOKE_URI)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientException e) {
			throw SocialUnlinkException.from("google revoke failed", e);
		}
	}
}
