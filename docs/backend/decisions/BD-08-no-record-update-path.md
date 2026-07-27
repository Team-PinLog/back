# BD-08. Record 수정 경로를 두지 않는다

- **상태**: Accepted
- **날짜**: 2026-07-27
- **관련**: S15P11A705-76
- **공용 계약**: [08_API_명세 §2.3](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)

## 맥락

Record 수정을 두고 공용 문서 넷이 서로 어긋나 있다.

| 문서 | Record 수정 |
|---|---|
| [02_정책_정의서](https://github.com/Team-PinLog/docs/blob/main/static/02_정책_정의서.md) | **있음** — 원칙 6·7과 §4「수정」에 재생성 플로우 8단계 |
| [10_MVP_기능범위](https://github.com/Team-PinLog/docs/blob/main/static/10_MVP_기능범위.md) | **있음** — "Record 조회·재생성 방식 수정·소프트 삭제", "Record 재생성 시 Collection 연결 승계" |
| [08_API_명세](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md) §2.3 | **없음** — Record 조작은 `POST` `GET` `GET by-place` `DELETE` `DELETE force`뿐 |
| [09_유저플로우](https://github.com/Team-PinLog/docs/blob/main/static/09_유저플로우.md) | **없음** — 관련 흐름 없음 |

즉 **구현할 계약(API)에도 사용자가 도달할 화면(유저플로우)에도 Record 수정이 없다.** Context 불변 전환(docs `c1b2869`) 때 Context 쪽만 정리되고 Record 쪽 서술이 남은 잔재로 보인다.

`record` 테이블이 가진 것은 `member_id`·`place_id`·시각뿐이므로, "Record 수정"이 실제로 뜻할 수 있는 것은 **place 교체** 하나다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 정책대로 재생성 수정 API를 추가한다 | 정책·MVP 문서와 일치 | place 교체마다 Record 행과 Collection 연결이 통째로 새로 생겨 삭제 더미가 늘어난다. Context 재연결까지 필요해 트랜잭션이 커진다 |
| (b) `place_id`를 in-place로 교체한다 | 행이 유지되고 연결도 그대로 | `(member_id, place_id)` 활성 부분 유니크와 충돌할 수 있다. 같은 Record가 다른 장소의 기록이 되어 `created_at`의 의미가 무너진다 |
| **(c) 수정 경로를 두지 않는다** | API·유저플로우의 현재 상태와 일치. 삭제+새 저장이라는 기존 경로로 충분 | 정책·MVP 문서 정정이 필요하다. 사용자는 장소를 잘못 고르면 지우고 다시 저장해야 한다 |

## 결정

**(c)를 채택한다. 능동적 선택.**

1. **[BD-06](BD-06-context-immutability.md)의 근거가 Record로 전이되지 않는다.** Context 불변은 "AI stale 판별 로직 제거"라는 구체적 이득이 있었다. Record에는 비동기 파생 데이터가 직접 달려 있지 않다 — Embedding과 Keyword는 Context에 달린다. 같은 이득이 없는데 형태만 맞추면 비용만 남는다.
2. **재생성은 문제를 키운다.** 삭제된 Record 더미를 늘리는 쪽은 in-place 수정이 아니라 재생성이다([BD-07](BD-07-soft-delete-no-restore.md)).
3. **기존 경로로 덮인다.** 장소를 바꾸려면 `GET /records/by-place`로 확인하고 `POST /records`로 새로 만들면 된다. 잘못 고른 Record는 `DELETE`한다.

## 결과

**감수하는 것**

- **공용 문서 정정이 남는다.** `02_정책_정의서`의 원칙 6·7과 §4「수정」, `10_MVP_기능범위`의 두 줄이 현재 계약과 어긋난 채 남아 있다. 이 문서들은 `Team-PinLog/docs` 소유라 백엔드가 단독으로 고칠 수 없다. **후속 조치로 제기해야 한다.**
- **사용자 경험** — 장소를 잘못 선택하면 수정이 아니라 삭제 후 재저장이다. 그 Record가 Collection의 마지막이면 연쇄 삭제 확인까지 거치게 된다([BD-10](BD-10-minimum-holding-invariants.md)).

**재검토 트리거**

- `record`에 사용자가 바꿀 수 있는 속성이 추가되면(예: 개인 별칭, 방문 여부) 그때 수정 경로를 다시 검토한다. 현재는 바꿀 것이 `place_id`뿐이라 성립하지 않는 논의다.
- 공용 문서 정정 논의에서 재생성 방식이 유지되기로 결론나면 이 결정을 `Superseded`로 바꾼다.
