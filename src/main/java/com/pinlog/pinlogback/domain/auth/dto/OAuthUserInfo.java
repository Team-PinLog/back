package com.pinlog.pinlogback.domain.auth.dto;

import java.util.Locale;
import java.util.Map;

import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * 공급자마다 다른 사용자 정보 응답을 하나의 형태로 정규화한 값.
 *
 * <p>식별 기준은 공급자가 발급한 식별자다. 이메일·닉네임은 바뀔 수 있어 쓰지 않는다(06 2.2).
 *
 * @param email 공급자가 제공하지 않거나 사용자가 동의하지 않으면 null이다.
 */
public record OAuthUserInfo(SocialProvider provider, String providerUserId, String email) {

	public static OAuthUserInfo from(String registrationId, Map<String, Object> attributes) {
		SocialProvider provider = toProvider(registrationId);
		return switch (provider) {
			case GOOGLE -> new OAuthUserInfo(
				provider,
				stringValue(attributes.get("sub")),
				stringValue(attributes.get("email")));
			// Kakao(kakao_account.email)·Naver(response.id)는 응답이 한 겹 감싸여 있어 별도 추출이
			// 필요하다. 두 공급자를 등록하는 티켓에서 함께 구현한다.
			case KAKAO, NAVER -> throw new UnsupportedSocialProviderException(registrationId);
		};
	}

	private static SocialProvider toProvider(String registrationId) {
		for (SocialProvider candidate : SocialProvider.values()) {
			if (candidate.name().equalsIgnoreCase(registrationId)) {
				return candidate;
			}
		}
		throw new UnsupportedSocialProviderException(registrationId);
	}

	private static String stringValue(Object attribute) {
		// 공급자에 따라 숫자로 오기도 한다. 저장은 항상 문자열이다(06 2.2).
		return attribute == null ? null : String.valueOf(attribute);
	}

	public String registrationId() {
		return provider.name().toLowerCase(Locale.ROOT);
	}
}
