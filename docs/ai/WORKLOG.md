# WORKLOG — Backend AI 파트

시간순 작업 로그입니다. 유형별 폴더(spec/proposals/implements/troubleshooting) 분산으로 인한 "내 작업 추적" 비용을 시간축 인덱스로 상쇄합니다. **이후 작업마다 한 줄씩 추가**합니다.

| 날짜 | 작업 | 관련 문서 |
|---|---|---|
| 2026-07-23 | Spring AI 연동·Feed 추천 구현 명세 작성 (back#1) | [spec/](spec/) |
| 2026-07-23 | back docs README를 백엔드 공통 허브로 재구성 (back#2) | [../README.md](../README.md) |
| 2026-07-23 | Flyway 도입 + ai 스키마·feed_event 마이그레이션 V1/V100~102 (back#3) | [implements](implements/2026-07-23-flyway-ai-schema-migration.md), [P21](proposals/P21-flyway-migration-convention.md)·[P22](proposals/P22-feed-event-ownership.md)·[P24](proposals/P24-flyway-schemas-unspecified.md) |
| 2026-07-23 | 작업 기록 신설(결정·트러블슈팅·리포트) (back#6) | [proposals/](proposals/)·[implements/](implements/)·[troubleshooting/](troubleshooting/) |
| 2026-07-23 | 문서 체계 재구조화 — spec/proposals/implements/troubleshooting + WORKLOG, ADR→P 번호 (back#6) | 이 트리 전체 |
| 2026-07-28 | back#58 Feed 합의 반영 — 구현 담당 김가현, MVP category·region 제외, 공용 API 계약(size 20·opaque cursor·별도 requestId) 우선, 향후 placeMeta 임베딩 전환 조건 기록 (S15P11A705-125) | [P42](proposals/P42-feed-mvp-without-place-metadata.md) · [Feed 명세](spec/feed-recommendation.md) |
| 2026-07-28 | 담당 긴급 정정 — Feed Spring 구현을 이정헌에게 재배치하고, 김가현은 Feed 범위에서 제외해 별도 추가 AI 기능 담당으로 분리 (S15P11A705-125) | [P42](proposals/P42-feed-mvp-without-place-metadata.md) |
| 2026-07-28 | 최신 `dev` 기준으로 Feed 계약을 재검토했다. 합의 당시보다 `dev`가 5커밋 앞서 있었고 그중 S15P11A705-117이 "요청 입력의 서버 방어 상한은 이름 붙은 상수 한 곳(`InputLimits`)에 모으고 `CursorPage.MAX_SIZE`와 같은 값을 쓴다"는 규약을 세웠다. Feed 명세의 `size=20`·opaque cursor는 공통 커서 계약(`DEFAULT_SIZE` 20 · `MAX_SIZE` 100 · `normalizeSize` 보정)과 이미 일치해 충돌이 없었고, 명세에 그 근거를 명시했다. 반면 `POST .../feed/events`의 배열 상한은 "예: 50"으로 미정이라 그 규약과 어긋나 100으로 고정했다. 함께 §4 코드블록만 `/api/core/v1/...`로 바뀌고 본문·`feed-event.md`·`feed-tests.md`는 옛 경로 `POST /feed/events`로 남아 있던 불일치도 정리했다 (S15P11A705-125) | [Feed 명세](spec/feed-recommendation.md) · [Feed 이벤트](spec/feed-event.md) |
| 2026-07-29 | back#74 리뷰 반영. `page-size` 10→20 변경에 탐색 슬롯이 따라가지 않아 탐색 비중이 20%→10%로 절반이 된 것을 발견하고 슬롯을 일반 2→4·Cold Start 3→6으로 올려 비율을 원복했다(정책 변경이 아니라 누락 보정). Place region 제거로 사라진 "AI 미완료 Collection이 점수를 얻는 경로"의 손실을 최신 채널 60→100 증량이 **부분적으로만** 보상한다는 점을 명시하고, Collection 쪽 Cold Start를 P42 "감수하는 것"에 추가했다. 후보 풀 상한 200과 채널 배분 합 200이 같아 잘라내기 규칙이 발동할 수 없다는 사실과 "10배 여유"의 실제값(팔로우 0인 사용자 기준 6배 남짓)을 바로잡았고, `InputLimits.FEED_EVENTS_MAX` 이름과 상한 초과 시 `400 INVALID_INPUT`을 못박았다. 이번에 고정한 API 계약 4가지를 `feed-tests.md`에 §11·E9로 추가했다 (S15P11A705-125) | [Feed 점수](spec/feed-scoring.md) · [Feed 테스트](spec/feed-tests.md) · [P42](proposals/P42-feed-mvp-without-place-metadata.md) |
