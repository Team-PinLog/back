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
	public void unlink(String accessToken) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("token", accessToken);
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
