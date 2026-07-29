package com.pinlog.pinlogback.domain.member.entity;

/**
 * 지원하는 소셜 로그인 공급자. MVP는 이 셋만 제공한다(docs/static/02_정책_정의서.md 2).
 *
 * <p>이름은 그대로 core.social_account.provider에 문자열로 저장되므로 변경하면 기존 행과 어긋난다.
 */
public enum SocialProvider {
	GOOGLE,
	KAKAO,
	NAVER
}
