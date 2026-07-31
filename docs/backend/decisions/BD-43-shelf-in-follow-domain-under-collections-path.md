# BD-43. 공개 책장 탐색을 Follow 도메인에 두고 경로는 `/v1/collections` 아래로 옮긴다

- **상태**: Accepted
- **날짜**: 2026-07-31
- **관련**: [S15P11A705-206](https://ssafy.atlassian.net/browse/S15P11A705-206) · [back#85](https://github.com/Team-PinLog/back/issues/85) · [back#58](https://github.com/Team-PinLog/back/issues/58) · [docs#35](https://github.com/Team-PinLog/docs/issues/35) · [docs#36](https://github.com/Team-PinLog/docs/pull/36)

## 맥락

작성자 공개 책장 탐색([08 §8.1](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md))이 구현되지 않아 프론트 호출이 운영에서 404였다. Feed 추천 MVP(S15P11A705-120, `[AI]` 티켓)가 이 Endpoint를 범위에서 제외했고 후속 티켓이 발행되지 않아 생긴 공백이다.

착수하면서 두 가지가 정해져 있지 않았다.

**하나, 어느 도메인에 두는가.** 공용 명세의 초안 경로가 `/feed/collections/{collectionId}/shelf`여서 Feed 도메인이 자연스러운 후보였다. 그런데 이 Endpoint는 추천 점수·Profile·`core.feed_event` 어느 것도 타지 않는다. 하는 일은 `collectionId`로 작성자를 찾아 그 작성자의 발행 Collection을 커서로 나열하고 요청자 기준 팔로우 상태를 얹는 것뿐이며, §9.3 `GET /follows/{followId}/collections`와 **같은 데이터를 다른 진입 키로 읽는다**.

Feed 런타임 구현의 담당자는 이정헌이고 AI 파트가 Feed 정책·계약을 소유한다(에픽 S15P11A705-111). back#58에서 AI 파트가 "shelf는 `-120` 범위 밖이고 AI 의존이 없으니 백엔드에서 먼저 끊어도 된다"고 명시적으로 넘겼다.

**둘, 경로를 그대로 둘 것인가.** 명세 안에서 이 Endpoint의 귀속이 엇갈려 있었다.

| 위치 | 상태 |
|---|---|
| §2.7 Endpoint 목록 "Feed·추천 이벤트" | 있음 |
| §8 상세 "공개 책장 탐색과 Follow" | 8.1로 상세 정의 |
| §10 상세 "Feed·추천 상세" | 없음 |

초기 문서화 이후 손댄 적이 없어 원래부터 엇갈린 상태였다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) `FeedController`에 매핑 추가 | 경로 접두어와 클래스가 일치한다 | 추천과 무관한 코드가 AI 파트 담당 클래스에 섞인다. `FeedService`는 native 질의 기반이라 재사용할 것이 없다 |
| (b) Follow 도메인에 신설, 경로는 `/v1/feed/...` 유지 | 대외 계약을 건드리지 않는다 | 패키지와 경로 접두어가 어긋난 채 영구히 남는다 |
| (c) Follow 도메인에 신설, 경로를 `/v1/collections/{collectionId}/shelf`로 옮긴다 | 진입 키가 경로에 드러나고, 바로 앞 호출(§13.6의 `GET /collections/{id}`) 밑으로 들어간다 | 공용 계약 변경이라 프론트 합의가 필요하다 |

## 결정

**(c)를 골랐다. 능동적 선택이다.**

결정적 이유는 둘이다.

**하나, 지금이 경로를 바꿀 수 있는 가장 싼 시점이다.** 이 Endpoint는 한 번도 동작한 적이 없다. 프론트가 호출하지만 성공 응답을 받은 적이 없으므로 깨질 동작이 없고, 프론트 수정은 요청 경로 문자열 하나다. 구현·배포된 뒤에는 같은 변경이 실제 마이그레이션이 된다.

**둘, 도메인 귀속이 경로 접두어가 아니라 하는 일로 정해져야 한다.** Feed에서 진입하는 것은 화면 흐름이고 조회 대상은 Collection이다. `/feed` 아래에 두면 "이 코드는 추천 파이프라인의 일부"라는 잘못된 신호가 남고, AI 파트와 백엔드의 소유 경계도 흐려진다.

Follow 도메인을 고른 이유는 응답에 요청자 기준 팔로우 상태가 실리고, 재사용하는 조회(`CollectionRepository.findPublished*`)와 판정(`memberRepository.isActive`)이 이미 `FollowService.listFollowedCollections`·`follow`가 쓰는 것과 같기 때문이다. Collection 도메인에 두면 Follow 조회 의존이 반대 방향으로 생긴다.

`ShelfController`의 `@RequestMapping`이 `/v1/collections`가 되어 `CollectionController`와 접두어를 나눠 쓴다. 한 도메인이 다른 도메인의 경로 접두어 아래에 Endpoint를 두는 첫 사례라 [`package-structure.md`](../../development/package-structure.md)에 근거를 남겼다.

## 결과

- 이 결정으로 감수하는 것:
  - **패키지(`follow`)와 경로 접두어(`/v1/collections`)가 다르다.** 경로만 보고 클래스를 찾으면 `CollectionController`에서 헛걸음한다. 양쪽 Javadoc에 서로를 가리키는 문장을 남겨 상쇄했다.
  - **공용 명세 수정이 선행 조건이다.** docs#36이 병합되기 전까지 구현과 계약이 어긋난 상태로 남는다. 프론트가 기존 경로 유지를 원하면 `ShelfController`의 매핑을 되돌린다 — 서비스·DTO·테스트는 그대로다.
  - **§9.3과 조회 로직이 닮았지만 합치지 않았다.** 진입 키와 응답 형태가 달라 지금은 공통화 이득이 작다.
- 재검토 트리거(이 조건이 오면 다시 논의):
  - 프론트가 docs#35에서 경로 변경에 반대하면 (b)로 되돌린다.
  - 06 §5.2가 요구한 "단일 공개 조회 서비스"를 실제로 만들게 되면(back#63) 이 서비스의 조회 조립도 그쪽으로 옮긴다 — 공개 응답 조립 지점이 세 곳이 된 상태다.
  - Shelf 전용 URL(새로고침·공유 가능한 페이지)이 필요해져 `member.public_id`를 도입하면(06 §5.3) 진입 키가 바뀌므로 경로를 다시 본다.
