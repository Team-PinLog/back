/**
 * 인증·인가 지원 타입. Security 필터 체인에 직접 물리는 구현들이다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한 이유: Spring Security 7이 자기 패키지를
 * {@code @NullMarked}로 선언해 파라미터가 non-null이다. 이 패키지를 마킹하지 않으면 우리 구현의
 * 파라미터가 "nullness 미상"으로 남아, non-null 파라미터를 재정의할 때 보증이 약해진다는 경고가 난다.
 *
 * <p>마킹 이후 이 패키지의 타입은 명시가 없으면 non-null이다. null이 될 수 있는 곳에는
 * {@code @Nullable}을 붙인다.
 */
@NullMarked
package com.pinlog.pinlogback.global.security;

import org.jspecify.annotations.NullMarked;
