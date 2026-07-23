# ADR-002: `core.feed_event`를 AI 구간(V102)에 배치

- **상태**: 채택
- **날짜**: 2026-07-23
- **관련 PR/커밋**: [back#3](https://github.com/Team-PinLog/back/pull/3) (`946df11`) / [back#1](https://github.com/Team-PinLog/back/pull/1) (Feed 명세)
- **소유 파트**: AI 파트

## 맥락

`feed_event`는 IMPRESSION / CLICK / SAVE 노출·상호작용을 append-only로 쌓는 로그 테이블이다. 스키마 위치는 `core`(다른 도메인 테이블과 조인·조회되므로)지만, 설계는 Feed 추천(AI 파트 소유)에서 나왔다. 즉 **테이블이 사는 스키마**(`core`)와 **마이그레이션을 작성·소유하는 파트**(AI)가 갈린다. 백엔드가 `V2~`에서 `core` 테이블 목록을 만들 때 이 테이블을 "빠졌다"고 오인해 다시 정의하면 `already exists`로 마이그레이션 전체가 깨진다.

## 결정

- `core.feed_event`를 **AI 구간인 `V102`**에 둔다. 스키마 위치(`core`)와 무관하게 소유 파트 구간을 따른다.
- 파일 상단과 [`db/migration/README.md`](../../src/main/resources/db/migration/README.md)에 **중복 정의 금지**를 명시한다.
- 구조: `id BIGINT GENERATED ALWAYS AS IDENTITY` PK, `member_id`·`collection_id`·`place_id`·`event`·`request_id UUID`·`position`·`created_at`. **FK 없음, `deleted_at` 없음.**

## 근거

- **소유 = 마이그레이션 구간**이라는 [ADR-001](ADR-001-flyway-version-convention.md) 원칙을 스키마 위치보다 우선한다. 그래야 "누가 이 테이블을 책임지는가"가 번호에서 일관되게 읽힌다.
- **FK 없음**: append-only 이벤트 로그는 참조 무결성보다 쓰기 처리량·보존이 중요하고, `member`/`collection`이 지워져도 로그는 남아야 분석에 쓰인다. 값만 보유한다.
- **`deleted_at` 없음**: 로그는 수정·소프트삭제 대상이 아니다. 보존 정책은 파티션/TTL로 다룰 문제.

## 버린 대안

- **백엔드 구간(`V2~`)에 두기**: 스키마가 `core`라는 이유로 백엔드가 소유. 하지만 Feed 스코어링·노출 패널티 설계 변경이 있을 때마다 소유 파트(AI)와 마이그레이션 작성 파트(백엔드)가 어긋나 조율 비용이 생긴다.

## 영향

- 백엔드는 `feed_event`를 `V2~`에서 다시 정의하지 않는다.
- Feed 추천 파이프라인은 이 테이블을 읽어 노출 패널티·상호작용 신호를 만든다([`feed/feed-event.md`](../feed/feed-event.md), [`feed/feed-scoring.md`](../feed/feed-scoring.md)).

## 검증

- `V102` 적용 후 `core.feed_event` 존재 및 CHECK(`event IN (...)`, `position >= 0`) 확인.
- `db/migration/README.md`의 "소유권 주의" 절로 다음 작성자에게 전달.
