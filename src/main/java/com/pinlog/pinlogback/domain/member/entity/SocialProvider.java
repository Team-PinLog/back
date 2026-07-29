package com.pinlog.pinlogback.domain.member.entity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 지원하는 소셜 로그인 공급자. MVP는 이 셋만 제공한다(docs/static/02_정책_정의서.md 2).
 *
 * <p>이름은 그대로 core.social_account.provider에 문자열로 저장되므로 변경하면 기존 행과 어긋난다.
 *
 * <p>OAuth 등록정보의 {@code registrationId}와 오가는 변환을 여기서 함께 갖는다. 이 enum이 이미
 * 자기 문자열 표현의 소유자이기 때문이다. 변환을 밖에 두면 <b>registrationId가 소문자 규칙을
 * 벗어나는 공급자</b>를 추가할 때(예: {@code apple-signin}) 만드는 쪽과 되읽는 쪽을 각각 고쳐야
 * 하는데, 한쪽만 고쳐도 컴파일은 통과한다. 증상은 진입 404와 콜백 실패로 <b>서로 다른 층에서</b>
 * 나와 같은 원인으로 보이지 않는다.
 */
public enum SocialProvider {
	GOOGLE,
	KAKAO,
	NAVER;

	/**
	 * 경로 변수나 {@code registrationId}로 받은 이름을 공급자로 바꾼다. 대소문자를 가리지 않는다.
	 *
	 * <p>예외를 던지지 않고 {@link Optional}을 돌려준다. 지원하지 않는 공급자를 <b>어떤 예외로
	 * 알릴지는 auth 도메인의 결정</b>이고, member 엔티티가 그 예외에 의존할 이유가 없다.
	 */
	public static Optional<SocialProvider> from(String name) {
		return Arrays.stream(values())
			.filter(candidate -> candidate.name().equalsIgnoreCase(name))
			.findFirst();
	}

	/** Spring OAuth2 등록정보의 {@code registrationId}. 로그인 진입 경로와 설정 키가 이 값을 쓴다. */
	public String registrationId() {
		return name().toLowerCase(Locale.ROOT);
	}
}
