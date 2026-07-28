/**
 * 애플리케이션 설정과 설정 프로퍼티.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-27과 같은 이유·같은 단위).
 * {@code JwtProperties.privateKey}처럼 <b>null이 정상값인 지점</b>이 있는데, 마킹되지 않은
 * 스코프에서는 {@code @Nullable} 표기가 도구에 아무 의미도 주지 못한다.
 *
 * <p>마킹 시점에 이 패키지를 전수 감사했고 {@code ApiResponseOpenApiCustomizer}의 두 파라미터를
 * 바로잡았다 — 이미 {@code null} 방어 분기를 갖고 있었으나 표기가 없어 거짓 보증이던 자리다.
 */
@NullMarked
package com.pinlog.pinlogback.global.config;

import org.jspecify.annotations.NullMarked;
