/**
 * 소셜 로그인과 세션 토큰 유스케이스.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-29과 같은 이유·같은 단위).
 * 쿠키에서 온 Refresh 토큰은 <b>없는 것이 정상</b>이라 {@code AuthTokenService}의 진입 파라미터가
 * nullable이며, 그 사실이 타입에 드러나야 호출자가 분기를 빠뜨리지 않는다.
 */
@NullMarked
package com.pinlog.pinlogback.domain.auth.service;

import org.jspecify.annotations.NullMarked;
