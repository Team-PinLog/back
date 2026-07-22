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

주의: 후보 채널 쿼리와 Keyword 집계 쿼리는 부분 인덱스, 크로스 스키마 조인, `FOR UPDATE SKIP LOCKED`를 사용하므로 **H2로 검증하지 않습니다.** Testcontainers 또는 로컬 `compose.yaml`의 PostgreSQL을 사용합니다. H2는 도메인 무관한 가벼운 실행에만 씁니다.

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
| P7 | `active = false` Preset | 응답에 없음 |

P3과 P4를 함께 검증해야 의미가 있습니다. 한쪽만 보면 "그냥 안 쓴다"와 구분되지 않습니다.

## 7. 이벤트 (통합)

| # | 항목 | 기대 |
|---|---|---|
| E1 | Feed 응답 후 IMPRESSION 기록 | 응답 항목 수만큼, `position` 포함 |
| E2 | IMPRESSION 기록 실패 | Feed 응답은 정상 |
| E3 | `POST /feed/events`로 IMPRESSION 전송 | 400 거부 |
| E4 | `POST /feed/events`의 `member_id` | 본문 값이 아니라 인증 컨텍스트 값이 저장됨 |
| E5 | 존재하지 않는 `collectionId` 포함 배열 | 해당 건만 버리고 나머지 저장, 204 |
| E6 | 노출 누적된 Collection | 다음 요청에서 순위 하락 |
| E7 | 노출이 cap을 넘어 누적됨 | 순위 하락이 상한에서 멈춤 (영구 배제 없음) |
| E8 | 같은 `request_id` 중복 IMPRESSION | 패널티 집계에서 1회로 계산 |

## 8. 다양성과 Cold Start (통합)

| # | 항목 | 기대 |
|---|---|---|
| D1 | 한 소유자가 상위를 독점하는 상황 | 응답 내 동일 소유자 최대 2건 |
| D2 | 소유자 상한으로 후보가 마름 | 상한을 완화해 개수를 채움, 빈 응답 아님 |
| D3 | 10건 응답의 탐색 슬롯 | 2건이 무작위 채널에서 채워짐 |
| D4 | 탐색 후보 0건 | 점수 상위로 채움, 빈 자리 없음 |
| D5 | Record 0건 신규 사용자 | Cold Start 경로, 최신·무작위 위주 응답 |
| D6 | Record는 있으나 AI Keyword가 0건 | Cold Start 경로, region·category로 점수 산출 |
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

| # | 시나리오 | back이 검증할 것 |
|---|---|---|
| 1 | v1 처리 중 Context가 v2로 수정됨 | Spring이 `body_version`을 증가시키고 `context_version`을 갱신, 두 status를 PENDING으로 리셋, `retry_count = 0` |
| 2 | 수정 직후 검색 | 검색 응답 조립 시 Version 불일치 Context가 제외됨. Core 재검증 통과 여부 확인 |
| 3 | 수정 직후 Keyword 조회 | 응답 쿼리의 `ck.context_version = ct.body_version` 조건으로 구 Keyword 미노출 |
| 5 | PROCESSING 중 서버 종료 | 10분 경과 후 재스캔이 해당 행을 후보로 선택하고 PENDING으로 되돌림 |
| 6 | 동일 Context 처리 요청 중복 | `FOR UPDATE SKIP LOCKED`로 동시 실행 두 스레드가 같은 행을 집지 않음 |
| 7 | 삭제 중 Embedding 완료 | Context 삭제 트랜잭션이 `embedding_status = CANCELLED`와 `is_deleted = true`를 같은 커밋에 반영 |
| 8 | 삭제 중 Keyword 완료 | 삭제 트랜잭션이 `keyword_status = CANCELLED`를 반영. 조회에서 해당 Context 제외 |
| 11 | `BLOCKED` Keyword | 소유자·타인 응답, Profile, Collection 특징 어디에도 나타나지 않음 (6장 P6) |
| 12 | 재스캔 후보 선택 후 Context 삭제 | 재조회에서 삭제를 확인하고 FastAPI 호출을 생략 |
| 13 | 타인 데이터 검색 시도 | 검색 요청의 `userId`가 인증 컨텍스트에서만 결정됨. 결과 Core 재검증에서 소유권 불일치 Record 제외 |
| 14 | 한 Record의 여러 Context 일치 | 검색 응답에 해당 Record가 1건만, similarity 순서 유지 |
| 15 | AI 미완료 Collection | Keyword 없이 기본 조회 성공, Feed 후보·응답에 정상 포함, `"keywords": []` |

추가로 back이 검증해야 하는 상태 관리 항목:

| # | 항목 | 기대 |
|---|---|---|
| A1 | 본문이 바뀌지 않는 Context 수정 | `body_version` 미증가, AI State 미변경, FastAPI 미호출 |
| A2 | 공백만 다른 수정 | 정규화 후 비교하여 `body_version` 미증가 |
| A3 | FastAPI 호출 실패 | Core 커밋 유지, PENDING 유지, 사용자 응답 성공 |
| A4 | Core 트랜잭션 롤백 | `AFTER_COMMIT` 리스너가 실행되지 않아 FastAPI 미호출 |
| A5 | `retry_count`가 최대치 도달 | 미완료 단계만 FAILED, COMPLETED 단계는 유지 |
| A6 | FAILED 상태 | 재스캔 후보로 선택되지 않음 |
| A7 | FAILED 상태에서 본문 수정 | PENDING으로 리셋되어 다시 처리 대상이 됨 |
| A8 | Record 삭제 | 모든 활성 Context의 두 status가 CANCELLED, `is_deleted = true` |
| A9 | 회원 탈퇴 | 해당 User의 모든 AI 파생 데이터가 CANCELLED·`is_deleted`, Feed·Library 즉시 제외 |
| A10 | 모든 상태 변경 | `updated_at` 갱신됨 |

A10은 눈에 띄지 않지만 누락 시 재스캔 만료 판정 전체가 오작동하므로 반드시 단언합니다.
