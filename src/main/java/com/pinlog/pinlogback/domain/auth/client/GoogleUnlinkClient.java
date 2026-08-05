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

import lombok.extern.slf4j.Slf4j;

/**
 * Google 연결 해제. {@code POST https://oauth2.googleapis.com/revoke}.
 *
 * <p>클라이언트 인증이 없다 — 토큰 자체가 자격이다. 성공은 {@code 200}이고, 이미 폐기된 토큰이나
 * 형식이 어긋난 요청은 {@code 400}이다.
 */
@Slf4j
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
		boolean hasRefreshToken = tokens.refreshToken() != null;
		if (!hasRefreshToken) {
			// 조용히 떨어지면 안 된다. 이 경로로 오면 2xx를 받아도 승인은 남고, 사용자는 자기 Google
			// 계정을 열어 보기 전까지 모른다 — 실제로 그렇게 한 바퀴 돌았다. 인가 요청에
			// access_type=offline·prompt=consent가 빠졌거나 Google이 주지 않은 것이다.
			log.warn("google revoke falls back to the access token — the grant will survive. "
				+ "check access_type=offline & prompt=consent on the withdrawal authorization request");
		}
		form.add("token", hasRefreshToken ? tokens.refreshToken() : tokens.accessToken());
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
