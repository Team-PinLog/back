/**
 * 공급자 연결 해제 호출(BD-48).
 *
 * <p>{@code global/security/oauth}가 아니라 여기 있는 이유는 소유자가 다르기 때문이다. 저쪽은
 * <b>우리 인증 상태를 만드는 필터 체인</b>의 부품이고, 이 패키지는 공급자에게 나가는 <b>바깥
 * 호출</b>이다 — 인증에 참여하지 않고, 인증이 끝난 뒤 탈퇴 흐름이 부른다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-29과 같은 이유·같은 단위).
 */
@NullMarked
package com.pinlog.pinlogback.domain.auth.client;

import org.jspecify.annotations.NullMarked;
