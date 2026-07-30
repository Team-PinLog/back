package com.pinlog.pinlogback.domain.auth.dto;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.pinlog.pinlogback.domain.auth.exception.UnsupportedSocialProviderException;
import com.pinlog.pinlogback.domain.member.entity.SocialProvider;

/**
 * 공급자마다 다른 사용자 정보 응답을 하나의 형태로 정규화한 값.
 *
 * <p>식별 기준은 공급자가 발급한 식별자다. 이메일·닉네임은 바뀔 수 있어 쓰지 않는다(06 2.2).
 *
 * <p><b>이메일도 필수다.</b> 설정 화면이 이 값을 반드시 표시해야 하므로 값 없는 계정을 두지
 * 않는다(06 §2.2). 공급자 콘솔이 이메일을 필수 동의로 두고 있어 사용자가 이 경로를 밟지는
 * 않지만, 그 설정이 깨졌을 때 값 없는 계정이 조용히 만들어지는 것을 여기서 막는다.
 */
public record OAuthUserInfo(
	SocialProvider provider,
	String providerUserId,
	String email
) {

	public static OAuthUserInfo from(String registrationId, Map<String, Object> attributes) {
		SocialProvider provider = SocialProvider.from(registrationId)
			.orElseThrow(() -> new UnsupportedSocialProviderException(registrationId));
		return switch (provider) {
			case GOOGLE -> new OAuthUserInfo(
				provider,
				required(attributes.get("sub"), "sub"),
				required(attributes.get("email"), "email"));
			// Kakao는 식별자를 최상위 id로 주고 이메일만 kakao_account 아래에 둔다.
			case KAKAO -> new OAuthUserInfo(
				provider,
				required(attributes.get("id"), "id"),
				required(nested(attributes, "kakao_account", "email"), "kakao_account.email"));
			// Naver는 본문 전체를 response로 한 겹 감싼다. 최상위에는 resultcode·message만 있다.
			case NAVER -> new OAuthUserInfo(
				provider,
				required(nested(attributes, "response", "id"), "response.id"),
				required(nested(attributes, "response", "email"), "response.email"));
		};
	}

	/** 한 겹 감싼 응답에서 값을 꺼낸다. 감싼 키가 없거나 Map이 아니면 {@code null}이다. */
	private static @Nullable Object nested(Map<String, Object> attributes, String wrapper, String key) {
		return attributes.get(wrapper) instanceof Map<?, ?> inner ? inner.get(key) : null;
	}

	/**
	 * 없으면 진행할 수 없는 속성을 여기서 끊는다. 조용히 통과시키면 해당 컬럼의 NOT NULL 위반이
	 * 되어 원인이 DB까지 내려간다.
	 *
	 * <p><b>식별자</b>는 user-name-attribute이므로 Spring이 앞단에서 걸러 주지만, 그 보증이 설정에
	 * 있고 타입에는 없어 한 번 더 확인한다. Naver는 예외다 — user-name-attribute가 감싼 키
	 * ({@code response})라서 Spring이 검사하는 것은 감싼 Map의 존재뿐이고 그 안의 {@code id}는
	 * 보지 않는다. 그쪽은 이 검사가 유일한 방어선이다.
	 *
	 * <p><b>이메일</b>은 세 공급자 모두 Spring이 보지 않는다. 필수 동의 설정이 1차 보장이고
	 * 이 검사가 그 설정에 의존하는 상태를 코드로 확인하는 자리다(06 §2.2).
	 */
	private static String required(@Nullable Object attribute, String path) {
		String value = stringValue(attribute);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("공급자 응답에 필수 속성이 없다: " + path);
		}
		return value;
	}

	private static @Nullable String stringValue(@Nullable Object attribute) {
		// 공급자에 따라 숫자로 오기도 한다. 저장은 항상 문자열이다(06 2.2).
		return attribute == null ? null : String.valueOf(attribute);
	}
}
