/**
 * 소셜 인증 결과를 도메인이 쓰는 형태로 옮긴 값.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-27과 같은 이유·같은 단위).
 * 공급자가 이메일을 주지 않는 경우가 정상이라 {@code OAuthUserInfo.email}이 실제로 null이 될 수
 * 있는데, 마킹 전에는 그 사실이 javadoc에만 있고 타입에는 없었다.
 */
@NullMarked
package com.pinlog.pinlogback.domain.auth.dto;

import org.jspecify.annotations.NullMarked;
