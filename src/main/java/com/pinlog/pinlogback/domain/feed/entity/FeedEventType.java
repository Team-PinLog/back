package com.pinlog.pinlogback.domain.feed.entity;

/**
 * {@code core.feed_event.event}의 도메인 값(feed-event 3장). 값 목록의 정본은 마이그레이션
 * {@code V102}의 {@code ck_feed_event_type} CHECK 제약이다.
 *
 * <p><b>테이블에 대응하는 {@code @Entity}는 두지 않는다.</b> {@code core.feed_event}는 AI 파트
 * 설계에서 나온 append-only 관측 로그이고 마이그레이션도 AI 구간(V102)이 소유한다 — 백엔드는
 * 조회·삽입만 한다(패키지 구조 규약). 그래도 이 enum이 {@code entity}에 있는 이유는 이것이
 * 그 테이블 컬럼의 도메인 타입이기 때문이다.
 */
public enum FeedEventType {

	/**
	 * <b>서버가 Feed 응답으로 전달했다</b>는 뜻이며 사용자 화면의 실제 viewport 노출이 아니다
	 * (feed-event 3.1). 기록 주체가 서버이므로 클라이언트가 보내면 거부한다.
	 */
	IMPRESSION,

	/** Feed 항목을 눌러 Collection 상세로 진입. 클라이언트가 보고한다. */
	CLICK,

	/** Feed에서 본 것을 자기 데이터로 가져감(팔로우·Record 추가). CLICK보다 강한 관심 신호다. */
	SAVE;

	/** 클라이언트가 보고할 수 있는 값인지. {@link #IMPRESSION}은 서버가 기록하므로 제외된다. */
	public boolean isClientReportable() {
		return this != IMPRESSION;
	}
}
