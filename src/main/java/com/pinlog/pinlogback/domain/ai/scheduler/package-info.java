/**
 * 시간이 기동시키는 AI 연동 작업 — 유실·정지된 처리의 복구.
 *
 * <p>{@code event}가 "커밋이 기동시키는 작업"이라면 여기는 "시간이 기동시키는 작업"이다. 두 축을
 * 가른 기준은 무엇이 호출을 촉발하는가이며, 그에 따라 트랜잭션 경계도 다르다 — 저쪽은 남의 트랜잭션이
 * 끝난 뒤에 얹히고, 이쪽은 자기 트랜잭션을 열고 닫는다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-29과 같은 이유·같은 단위).
 * {@code @NullMarked}는 하위 패키지로 상속되지 않으므로 상위 {@code domain.ai}에 선언돼 있어도
 * 여기에 다시 적어야 한다.
 */
@NullMarked
package com.pinlog.pinlogback.domain.ai.scheduler;

import org.jspecify.annotations.NullMarked;
