package com.pinlog.pinlogback.domain.auth.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.pinlog.pinlogback.domain.auth.exception.SocialUnlinkException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * Kakao 연결 끊기. {@code POST https://kapi.kakao.com/v1/user/unlink}, 사용자 토큰 방식.
 *
 * <p>어드민 키 방식을 쓰지 않는다 — 그 앱의 <b>어떤 사용자에게든</b> 해제와 정보 조회가 가능한
 * 고권한 키를 상시 보관해야 해서, 목적에 비해 권한 범위가 과하다(BD-48 §①).
 *
 * <p>Kakao는 회원 탈퇴 시 연결 끊기를 <b>약관으로 요구한다.</b> 실패를 삼킬 수 없는 이유다.
 */
@Component
public class KakaoUnlinkClient implements SocialUnlinkClient {

	private static final String UNLINK_URI = "https://kapi.kakao.com/v1/user/unlink";

	private final RestClient restClient;

	public KakaoUnlinkClient(@Qualifier("socialUnlinkRestClient") RestClient socialUnlinkRestClient) {
		this.restClient = socialUnlinkRestClient;
	}

	@Override
	public SocialProvider provider() {
		return SocialProvider.KAKAO;
	}

	@Override
	public void unlink(String accessToken) {
		try {
			restClient.post()
				.uri(UNLINK_URI)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientException e) {
			// 응답 본문의 id는 검사하지 않는다. 2xx가 곧 해제 완료이고, 본문을 더 보는 것은
			// 계약에 없는 것을 계약으로 만드는 일이다.
			throw new SocialUnlinkException("kakao unlink failed", e);
		}
	}
}
