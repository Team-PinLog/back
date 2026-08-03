# Feed 테스트와 back 소관 검증 시나리오

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

Feed 기능의 테스트 항목과, 공용 계약 §16 필수 검증 시나리오 중 back 파트가 구현·검증해야 하는 항목을 정의합니다.

`ai` 파트가 소관인 시나리오(FastAPI 내부 상태 전이, 저장 직전 잠금 검사, Preset Profile 불일치 판정)는 여기서 다루지 않습니다. back은 그 시나리오의 **전제 조건을 만드는 쪽**과 **결과를 응답에 반영하는 쪽**만 검증합니다.

## 2. 테스트 계층

| 계층 | 대상 | 방식 |
|---|---|---|
| 단위 | 점수 공식, weighted Jaccard, 다양성 조정, Cold Start 판정 | 순수 자바. DB·Redis 없음 |
| 슬라이스 | 후보 채널 쿼리, 특징 집계 쿼리, 재검증 쿼리 | `@DataJpaTest` + PostgreSQL |
| 통합 | Feed API 전체, Cache 폴백, 이벤트 수집 | `@SpringBootTest` + PostgreSQL + Redis |

주의: 후보 채널 쿼리와 Keyword 집계 쿼리는 부분 인덱스, 크로스 스키마 조인, `FOR UPDATE SKIP LOCKED`를 사용합니다. DB가 필요한 모든 테스트는 PostgreSQL Testcontainers를 사용하며, H2는 어떤 테스트에도 사용하지 않습니다.

## 3. 점수 계산 (단위)

| # | 항목 | 기대 |
|---|---|---|
| S1 | weighted Jaccard: 동일 분포 | 1.0 |
| S2 | weighted Jaccard: 교집합 없음 | 0.0 |
| S3 | weighted Jaccard: 한쪽이 빈 분포 | 0.0, 예외 없음 |
| S4 | weighted Jaccard: 양쪽 다 빈 분포 | 0.0, 0으로 나누기 없음 |
| S5 | Keyword 수가 많은 Collection이 무조건 유리하지 않음 | 크기 편향 없음 확인 |
| S6 | followSignal 이분값 | 팔로우 채널 출처만 1.0 |
| S7 | recency 지수 감쇠 | half-life 시점에 약 0.5, 오래돼도 0이 아님 |
| S8 | impression penalty가 cap을 넘지 않음 | `min(impressions, cap)` |
| S9 | 각 항이 0~1 범위 | 정규화 검증 |
| S10 | 가중치를 설정으로 바꾸면 순위가 바뀜 | 상수 하드코딩 없음 확인 |

## 4. 후보 생성 (슬라이스)

| # | 항목 | 기대 |
|---|---|---|
| C1 | 본인 소유 Collection | 모든 채널에서 제외 |
| C2 | `deleted_at IS NOT NULL` Collection | 후보 제외 |
| C3 | `is_published = false` Collection | 후보 제외 |
| C4 | 탈퇴 User의 Collection | 후보 제외 |
| C5 | `record_count = 0` Collection | 후보 제외 |
| C6 | 여러 채널에 걸린 Collection | 중복 없이 1건, 팔로우 출처 보존 |
| C7 | 후보 풀 상한 | 설정값(200)을 넘지 않음 |
| C8 | 팔로우가 0건인 사용자 | 팔로우 채널 0건, 나머지 채널로 후보 구성 |
| C9 | 전체 Collection이 후보 풀보다 적을 때 | 있는 만큼 반환, 예외 없음 |

## 5. Cache (통합)

| # | 항목 | 기대 |
|---|---|---|
| K1 | Profile TTL | 6시간으로 설정됨 |
| K2 | Collection 특징 TTL | 1시간으로 설정됨 |
| K3 | Record 추가 후 즉시 재요청 | Cache 무효화가 일어나지 않음 (TTL 기반 정책 확인) |
| K4 | Cache에 담긴 Collection이 그 사이 삭제됨 | 최종 응답에서 제외됨 |
| K5 | Cache에 담긴 Collection 소유자가 탈퇴함 | 최종 응답에서 제외됨 |
| K6 | Cache에 담긴 Collection이 비공개로 전환됨 | 최종 응답에서 제외됨 |
| K7 | Redis 다운 | Feed가 DB 폴백으로 정상 응답 (500 아님) |
| K8 | 역직렬화 실패 (구조 변경 시뮬레이션) | Cache miss로 처리, 예외 전파 없음 |
| K9 | Feed Session 만료 후 페이지 요청 | 새 Session으로 첫 페이지부터, 오류 아님 |
| K10 | 같은 Session의 연속 페이지 | 항목 중복·누락 없음 |

K4~K6이 stale 방어의 핵심 테스트입니다. Cache를 인위적으로 채운 뒤 DB만 바꾸고 요청하는 방식으로 검증합니다.

## 6. 공개 범위 (통합)

| # | 항목 | 기대 |
|---|---|---|
| P1 | Feed 응답에 `context.body` | 포함되지 않음 (DTO에 필드 자체가 없음) |
| P2 | Feed 응답에 `member.id` | 포함되지 않음 |
| P3 | 타인 Collection 특징에 `PRIVATE_ONLY` Keyword | 사용되지 않음 |
| P4 | 본인 Profile에 `PRIVATE_ONLY` Keyword | 사용됨 |
| P5 | `PRIVATE_ONLY` Keyword가 응답에 노출 | 되지 않음 |
| P6 | `BLOCKED` Keyword | Profile·특징·응답 어디에도 없음 |
| P7 | `is_active = false` Preset | 응답에 없음 |

P3과 P4를 함께 검증해야 의미가 있습니다. 한쪽만 보면 "그냥 안 쓴다"와 구분되지 않습니다.

## 7. 이벤트 (통합)

| # | 항목 | 기대 |
|---|---|---|
| E1 | Feed 응답 후 IMPRESSION 기록 | 응답 항목 수만큼, `position` 포함 |
| E2 | IMPRESSION 기록 실패 | Feed 응답은 정상 |
| E3 | `POST /api/core/v1/feed/events`로 IMPRESSION 전송 | 400 거부 |
| E4 | `POST /api/core/v1/feed/events`의 `member_id` | 본문 값이 아니라 인증 컨텍스트 값이 저장됨 |
| E5 | 존재하지 않는 `collectionId` 포함 배열 | 해당 건만 버리고 나머지 저장, 204 |
| E6 | 노출 누적된 Collection | 다음 요청에서 순위 하락 |
| E7 | 노출이 cap을 넘어 누적됨 | 순위 하락이 상한에서 멈춤 (영구 배제 없음) |
| E8 | 같은 `request_id` 중복 IMPRESSION | 패널티 집계에서 1회로 계산 |
| E9 | `events` 배열이 `InputLimits.FEED_EVENTS_MAX`(100)를 초과 | `400 INVALID_INPUT`. 초과분만 잘라내 204로 응답하지 않음 |

E5와 E9를 함께 봐야 이 엔드포인트의 계약이 드러납니다. 개별 항목의 무효는 조용히 버리고 204(E5), 요청 전체의 크기 위반은 400으로 거부(E9)입니다. 두 규칙이 갈리는 지점이므로 한쪽만 검증하면 구현이 어느 쪽으로도 흘러갈 수 있습니다.

## 8. 다양성과 Cold Start (통합)

| # | 항목 | 기대 |
|---|---|---|
| D1 | 한 소유자가 상위를 독점하는 상황 | 응답 내 동일 소유자 최대 2건 |
| D2 | 소유자 상한으로 후보가 마름 | 상한을 완화해 개수를 채움, 빈 응답 아님 |
| D3 | 20건 응답의 탐색 슬롯 | 4건이 무작위 채널에서 채워짐 (탐색 비중 20%) |
| D4 | 탐색 후보 0건 | 점수 상위로 채움, 빈 자리 없음 |
| D5 | Record 0건 신규 사용자 | Cold Start 경로, 최신·무작위 위주 응답 |
| D6 | Record는 있으나 AI Keyword가 0건 | Cold Start 경로, 팔로우·최신·무작위로 응답 |
| D7 | Profile 계산 실패 | Cold Start 폴백, 오류 아님 |

## 9. 성능 경계 (통합)

| # | 항목 | 기대 |
|---|---|---|
| N1 | Feed 요청 처리 중 FastAPI 호출 | **0회** |
| N2 | Feed 요청 처리 중 Embedding API 호출 | **0회** |
| N3 | Feed 요청 처리 중 LLM API 호출 | **0회** |
| N4 | 후보 200건에 대한 특징 조회 | DB 쿼리 1회 (N+1 없음) |
| N5 | Keyword 집계 | `record_id IN (...)` 일괄 조회 1회 |
| N6 | 노출 이벤트 집계 | 후보 id 목록으로 1회 |
| N7 | Core 재검증 | 최종 선정분에만 1회 |

N1~N3은 Client Bean을 Mock으로 주입하고 **호출이 0회임을 단언**하는 방식으로 검증합니다. 이 경계는 문서상 원칙이 아니라 테스트로 고정해야 합니다. N4~N7은 쿼리 카운터로 검증합니다.

## 10. 공용 §16 시나리오 중 back 소관

각 시나리오의 SQL과 Fixture는 이 표를 기준으로 back 저장소 테스트에서 관리합니다.

번호는 공용 계약 §16의 시나리오 번호입니다.

| # | 시나리오 | back이 검증할 것 |
|---|---|---|
| 1 | 처리 중 사용자가 Context 본문 수정 | 한 트랜잭션에서 구 Context 소프트 삭제 + 두 status CANCELLED + 구 Embedding `is_deleted = true`, 신 Context가 **새 `context_id`** 와 PENDING(`retry_count = 0`)으로 INSERT |
| 3 | 수정 후 검색 | 구 Context가 `is_deleted = true`와 `embedding_status = CANCELLED`로 검색에서 제외됨. Core 재검증 통과 여부 확인 |
| 4 | 수정 후 Keyword 조회 | 구 Context Keyword가 직전까지 COMPLETED였더라도 `keyword_status = CANCELLED`로 응답에서 제외됨 |
| 8 | `PROCESSING` 중 서버 종료 | 10분 경과 후 재스캔이 해당 행을 후보로 선택하고 재요청. Spring이 status를 직접 되돌리지 않음 |
| 9 | 동일 Context 처리 요청 중복 | `FOR UPDATE SKIP LOCKED`로 동시 실행 두 스레드가 같은 행을 집지 않음 |
| 10 | 삭제 중 Embedding 완료 | Context 삭제 트랜잭션이 `embedding_status = CANCELLED`와 `is_deleted = true`를 같은 커밋에 반영 |
| 11 | 삭제 중 Keyword 완료 | 삭제 트랜잭션이 `keyword_status = CANCELLED`를 반영. 조회에서 해당 Context 제외 |
| 12 | Embedding Row가 없는 상태에서 삭제·수정 | `is_deleted` UPDATE 영향 행 수가 0이어도 정상 처리. 예외·경고 없음 |
| 15 | `BLOCKED` Keyword | 소유자·타인 응답, Profile, Collection 특징 어디에도 나타나지 않음 (6장 P6) |
| 16 | `retry_count = 3` stale 상태 | Finalizer가 미완료 단계만 FAILED, COMPLETED 단계는 유지 |
| 17 | Finalizer 처리 중 Context 삭제 | CANCELLED 우선. Finalizer가 CANCELLED를 FAILED로 덮어쓰지 않음 |
| 18 | 재스캔 후보 선택 후 Context 삭제 | 재조회에서 삭제를 확인하고 FastAPI 호출을 생략 |
| 19 | 타인 데이터 검색 시도 | 검색 요청의 `userId`가 인증 컨텍스트에서만 결정됨. 결과 Core 재검증에서 소유권 불일치 Record 제외 |
| 20 | 한 Record의 여러 Context 일치 | 검색 응답에 해당 Record가 1건만, similarity 순서 유지 |
| 21 | AI 미완료 Collection | Keyword 없이 기본 조회 성공, Feed 후보·응답에 정상 포함, `"keywords": []` |

2·5·6·7·13·14번은 FastAPI 측 저장 거부와 판정 로직이 대상이므로 ai 레포 테스트가 소관입니다.

추가로 back이 검증해야 하는 상태 관리 항목:

| # | 항목 | 기대 |
|---|---|---|
| A1 | 본문이 바뀌지 않는 Context 수정 | 정규화 후 본문이 동일하면 Context 미교체, AI State 미변경, FastAPI 미호출, 응답 `contextId` 불변 |
| A2 | 공백만 다른 수정 | 정규화 후 비교하여 Context 미교체 |
| A3 | FastAPI 호출 실패 | Core 커밋 유지, PENDING 유지, 사용자 응답 성공. 상태를 FAILED로 쓰지 않음 |
| A4 | Core 트랜잭션 롤백 | `AFTER_COMMIT` 리스너가 실행되지 않아 FastAPI 미호출 |
| A5 | 수정 트랜잭션 실패 | 구 Context 삭제와 신 Context 생성이 **모두** 롤백. 구 Context와 구 State가 수정 이전 상태 유지 |
| A6 | FAILED 상태 | 재스캔 후보로 선택되지 않음 |
| A7 | FAILED 상태에서 본문 수정 | 구 State는 CANCELLED가 되고, 신 Context가 새 `context_id`·`retry_count = 0`·PENDING으로 처리됨. 기존 State를 PENDING으로 되돌리지 않음 |
| A8 | Record 삭제 | 모든 활성 Context의 두 status가 CANCELLED, `is_deleted = true` |
| A9 | 회원 탈퇴 | 해당 User의 모든 AI 파생 데이터가 CANCELLED·`is_deleted`, Feed·Library 즉시 제외 |
| A10 | 모든 상태 변경 | `updated_at` 갱신됨 |
| A11 | 수정 커밋 후 FastAPI 호출 실패 | 신 Context와 PENDING State 유지, 재스캔이 복구 |
| A12 | 수정 API 응답 | 응답에 **새 `contextId`** 포함. 구 `contextId`를 반환하지 않음 |
| A13 | 마지막 Context 수정 | 신 Context INSERT가 삭제보다 먼저 실행되어 "마지막 Context 개별 삭제 불가" 가드에 걸리지 않고 정상 교체됨 |

A10은 눈에 띄지 않지만 누락 시 재스캔 만료 판정 전체가 오작동하므로 반드시 단언합니다. A12는 클라이언트 계약이 깨지는 지점이므로 컨트롤러 레벨에서 단언합니다.

## 11. Feed API 계약 (통합)

S15P11A705-125에서 못박은 요청·응답 계약입니다. 명세에만 두면 S15P11A705-120 구현에서 빠지므로 테스트로 고정합니다.

| # | 항목 | 기대 |
|---|---|---|
| Q1 | `GET /api/core/v1/feed/collections`를 `size` 없이 호출 | `CursorPage.DEFAULT_SIZE`인 **20**건. Feed 전용 기본값을 따로 두지 않음 |
| Q2 | `size`가 `CursorPage.MAX_SIZE`(100)를 초과 | `normalizeSize`가 100으로 보정. 400이 아님 |
| Q3 | `size`가 0 이하 | `normalizeSize`가 기본값 20으로 보정 |
| Q4 | 응답 `nextCursor`를 그대로 다음 요청에 전달 | 같은 Session의 다음 페이지. 항목 중복·누락 없음 (K10과 함께 확인) |
| Q5 | `cursor` 문자열 내용 | 내부 구조(offset·id·점수)가 드러나지 않는 opaque 값 |
| Q6 | 위조·손상된 `cursor` | 새 Session 첫 페이지로 처리하거나 400. 500이 아님 |
| Q7 | Feed 응답 본문의 `requestId` | `data` 안의 **별도 필드**. cursor에 인코딩하거나 항목마다 반복하지 않음 |
| Q8 | 응답의 `requestId`를 CLICK·SAVE payload로 되돌려 보냄 | 같은 값으로 `core.feed_event.request_id`에 저장됨 |

Q1~Q3은 Feed가 공통 커서 계약을 그대로 쓴다는 확인입니다. Feed 컨트롤러가 자체 상한을 도입하면 실패해야 합니다 — S15P11A705-117이 세운 "서버 방어 상한의 답은 하나" 규약이 깨지는 지점입니다. Q5는 응답을 문자열로 파싱해 내부 식별자가 노출되지 않음을 단언합니다.

## 12. 표시 Keyword 선정과 정렬

S15P11A705-278([P46](../proposals/P46-feed-keyword-display-order.md))이 정한 규칙입니다. KW1~KW7은 순수 자바 단위 테스트이고 KW8~KW11은 통합입니다.

| # | 항목 | 기대 |
|---|---|---|
| KW1 | Keyword가 5개 이상인 Collection | 응답에 **4개**만. 상한이 상수이며 설정으로 바뀌지 않음 |
| KW2 | 빈도가 갈리는 Collection | 같은 축 안에서 빈도 높은 것이 앞 |
| KW3 | 빈도가 전부 같고 축이 4개 | 상위 4개가 **서로 다른 축**. 실측 63%가 이 상태이므로 규칙의 핵심 |
| KW4 | 축이 하나뿐이고 Keyword가 4개 이상 | 그래도 **4개**를 채움. 축당 1개로 굶기지 않음 |
| KW5 | 축·빈도가 모두 같은 동점 | `keyword_preset.id` 오름차순. 입력 Map 순서를 섞어도 결과 동일 |
| KW6 | 표시값을 못 찾은 `code`가 섞임 | 그 항목만 빠지고 **다음 후보가 자리를 채워** 4개 유지 |
| KW7 | 서로 다른 `code`의 표시값이 겹침 | 하나만 남고 **다음 후보가 자리를 채워** 4개 유지 |
| KW8 | Keyword 4개 미만인 Collection | 있는 만큼 반환, 응답 정상 |
| KW9 | Keyword 0개인 Collection | `[]`. 오류 아님 (10장 시나리오 21과 같은 계약) |
| KW10 | 같은 요청을 두 번 | `keywords` 순서가 완전히 동일 |
| KW11 | 표시값(`display_name`)만 바꾼 뒤 재요청 | 선정된 Keyword **집합과 순서가 그대로**. 라벨이 순서를 움직이지 않음 |

KW3과 KW4를 함께 봐야 규칙이 드러납니다. 축을 1순위로 두되 **축당 1개로 못 박지는 않는다**가 결정이고, 한쪽만 검증하면 구현이 어느 쪽으로도 흘러갑니다.

KW11은 `S15P11A705-252`가 점수 계산의 키를 `code`로 못 박은 것과 같은 이유입니다 — 표시값을 정렬 근거로 삼으면 라벨을 고친 날 카드 구성이 조용히 바뀌고, 오류도 안 나고 다른 테스트도 안 깨집니다.

KW6·KW7은 "자리를 비우지 않는다"가 계약이라는 확인입니다. 필터를 자르기 **뒤에** 두면 4개를 요청했는데 3개가 나가고, 증상이 데이터에 따라 산발적으로만 나타납니다.
