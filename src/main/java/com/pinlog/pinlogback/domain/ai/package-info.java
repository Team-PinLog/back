/**
 * {@code ai} 스키마와 FastAPI AI Server에 대한 백엔드 쪽 연동.
 *
 * <p>도메인 애그리거트가 아니라 <b>파트 경계</b>다. {@code ai} 스키마의 소유는 AI 파트지만 몇몇
 * 컬럼은 백엔드가 쓰고(데이터모델 1.3 쓰기 매트릭스), 그 쓰기와 FastAPI 호출을 한곳에 모은다.
 * 흩어 두면 "백엔드가 ai 스키마의 무엇을 쓰는가"를 파일 전체를 훑어야 알 수 있다.
 *
 * <p>{@link org.jspecify.annotations.NullMarked}로 선언한다(BD-29과 같은 이유·같은 단위).
 */
@NullMarked
package com.pinlog.pinlogback.domain.ai;

import org.jspecify.annotations.NullMarked;
