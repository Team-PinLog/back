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
 * <p><b>본문이 아니라 상태 코드로 판단한다.</b> 공급자 문서 §4.3이 그렇게 요구한다.
 *
 * <blockquote>본 API는 입력한 토큰의 유효성과 무관하게, 폐기 자체가 정상 수행되면 200을 반환합니다.
 * 따라서 클라이언트는 <b>응답 본문이 아닌 HTTP 상태 코드를 기준으로</b> 결과를
 * 판단해야 합니다.</blockquote>
 *
 * <p>성공은 <b>본문 없는 200</b>이고, 실패는 상태 코드(400·401·503)와 함께 {@code error}·
 * {@code error_description}을 본문으로 준다. 그래서 본문을 파싱할 이유가 없다 — 200에는 볼 것이
 * 없고, 실패는 이미 상태 코드에서 갈린다.
 *
 * <p><b>3사 중 유일하게 이미 폐기된 토큰에도 200을 준다</b>(§4.3의 상태 코드 표). Google은 400이라
 * 재시도 의미가 다르다 — {@code SocialUnlinkException.from} 참고.
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
	public void unlink(ProviderTokens tokens) {
		String accessToken = tokens.accessToken();
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
		try {
			restClient.post()
				.uri(REVOKE_URI)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientException e) {
			throw SocialUnlinkException.from("naver revoke failed", e);
		}
	}
}
