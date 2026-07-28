/**
 * 도메인에 속하지 않는 애플리케이션 전역 설정.
 *
 * <p>보안 설정은 여기 없다. {@code SecurityConfig}·{@code JwtProperties}와 인증 관련 MVC 설정은
 * 모두 {@code global.security} 아래에 있다 — 설정과 그 설정이 조립하는 구현이 떨어져 있으면
 * 한쪽만 고치게 된다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-27). 마킹 시점에 전수 감사했고
 * {@code ApiResponseOpenApiCustomizer}의 두 파라미터를 바로잡았다 — 이미 {@code null} 방어 분기를
 * 갖고 있었으나 표기가 없어 거짓 보증이던 자리다.
 */
@NullMarked
package com.pinlog.pinlogback.global.config;

import org.jspecify.annotations.NullMarked;
