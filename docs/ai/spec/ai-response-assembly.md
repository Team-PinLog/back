# Keyword Visibility 응답 조립

> 현재 코드가 없는 구현 예정 명세입니다.
> 공용 계약은 Team-PinLog/docs의 `static/05_AI_설계.md`를 따릅니다.

## 1. 범위

`ai.context_keyword` 판정 결과를 API 응답으로 내보낼 때 Visibility를 어떻게 반영하는지, AI 미완료 상태를 어떻게 표현하는지, 자연어 검색 결과를 Core 기준으로 어떻게 재검증하는지 정의합니다.

Visibility 값의 의미(`PUBLIC` / `PRIVATE_ONLY` / `BLOCKED`)와 비식별화 원칙은 공용 계약이 원본입니다.

## 2. Visibility 적용 규칙

| Visibility | 소유자 응답 | 타인 응답 | 본인 개인화 Profile | 타인 Collection Feed 특징 |
|---|---|---|---|---|
| `PUBLIC` | O | O | O | O |
| `PRIVATE_ONLY` | O | X | O | X |
| `BLOCKED` | X | X | X | X |

읽는 방법:

- 소유자 응답에는 `PUBLIC`과 `PRIVATE_ONLY`가 들어갑니다.
- 타인 응답에는 `PUBLIC`만 들어갑니다.
- `BLOCKED`는 어떤 응답에도, 어떤 계산에도 들어가지 않습니다. 조회 자체에서 걸러냅니다.

`PRIVATE_ONLY`가 별도 등급인 이유는, 타인에게 보이지 않아야 할 정보가 추천 근거를 통해 간접적으로 드러나는 것을 막기 위해서입니다. 개인화 계산에는 쓰되 타인 Collection의 특징으로는 쓰지 않습니다.

## 3. 쿼리·DTO 분리

응답 조립 단계에서 필드를 걸러내는 방식은 한 번 빠뜨리면 곧바로 개인정보 노출입니다. 실패가 안전한 방향으로 나도록 **조회 단계에서 분리**합니다.

| 방식 | 실수했을 때 |
|---|---|
| 단일 쿼리 + DTO 필터링 | 개인정보 유출 |
| 공개용 복제 테이블 | 동기화 누락 시 낡은 데이터 노출 |
| **쿼리·DTO 분리 (채택)** | 필드가 없어 컴파일 오류 또는 null |

### 3.1 리포지토리 분리

메서드명에 대상 범위를 명시합니다.

```text
findKeywordsForOwner(recordIds, memberId)   -- visibility IN ('PUBLIC','PRIVATE_ONLY')
findPublicKeywords(recordIds)               -- visibility = 'PUBLIC'
```

- Visibility 필터는 **SQL의 WHERE 절**에 둡니다. 자바 코드의 stream filter로 두지 않습니다. 조건이 코드에 있으면 새 호출 경로가 추가될 때 조용히 누락됩니다.
- `BLOCKED`는 두 메서드 어디에도 등장하지 않습니다. `visibility IN (...)` 화이트리스트로 작성하고 `visibility <> 'BLOCKED'` 같은 블랙리스트로 작성하지 않습니다. Visibility 값이 나중에 추가될 때 블랙리스트는 새 값을 통과시킵니다.
- `kp.is_active = true`를 함께 확인합니다. 폐기된 Preset은 행 삭제가 아니라 `is_active = false`로 처리되므로 필터가 없으면 계속 노출됩니다.

### 3.2 DTO 분리

소유자용과 공개용 응답 클래스를 **상속 없이 별개로** 정의합니다.

```text
RecordDetailForOwnerResponse   -- contextBody 포함, keywords 포함
RecordPublicResponse           -- contextBody 없음, keywords 포함
```

- 상속이나 조건부 직렬화(`@JsonView`, `@JsonInclude` 분기)를 쓰지 않습니다. 부모에 필드가 추가되면 자식이 조용히 상속받아 노출됩니다.
- 공개용 DTO에는 `context.body` 필드가 **아예 없습니다**. 실수해도 값이 담길 자리가 없습니다.
- `member.id`는 어떤 응답에도 포함하지 않습니다.

### 3.3 경로 통합

타인이 데이터에 접근하는 경로는 Feed, Collection 상세, 팔로우한 Shelf 셋뿐입니다. 이 셋이 **단일 공개 조회 서비스**를 거치게 해서 노출 필드를 한 곳에서 관리합니다. 각 컨트롤러가 자기 쿼리를 직접 짜면 규약이 흩어집니다.

## 4. 조회 쿼리

### 4.1 공개용 Keyword

```sql
SELECT ct.record_id, kp.code, kp.display_name, kp.category
FROM core.context ct
JOIN ai.context_ai_state st ON st.context_id = ct.id
JOIN ai.context_keyword  ck ON ck.context_id = ct.id
JOIN ai.keyword_preset   kp ON kp.id = ck.keyword_id
WHERE ct.record_id IN (:recordIds)
  AND ct.deleted_at IS NULL
  AND st.keyword_status = 'COMPLETED'
  AND kp.visibility = 'PUBLIC'
  AND kp.is_active = true;
```

소유자용은 마지막 Visibility 조건만 `IN ('PUBLIC','PRIVATE_ONLY')`로 바꿉니다.

정리하면 두 경로의 판정 조건은 다음과 같습니다.

| 대상 | 조건 |
|---|---|
| 본인 | `keyword_status = COMPLETED` AND Preset `is_active = true` AND `visibility IN ('PUBLIC','PRIVATE_ONLY')` |
| 타인 | `keyword_status = COMPLETED` AND Preset `is_active = true` AND `visibility = 'PUBLIC'` |

세 조건이 각각 담당하는 것:

- `st.keyword_status = 'COMPLETED'` — 미완료·실패·취소 상태 제외
- `kp.is_active = true` — 폐기된 Preset 제외
- `kp.visibility` — 공개 범위 제한

**Context 본문 버전을 비교하는 조건은 없습니다.** Context는 불변이므로 `context_id`가 곧 본문의 정체성이고, `ai.context_keyword`에도 `ai.context_ai_state`에도 본문 버전 컬럼이 존재하지 않습니다. 조회 판정은 State 조인 하나로 끝납니다.

### 4.2 구 Context가 제외되는 방식

Context가 수정되면 구 Context는 소프트 삭제되고 두 status가 CANCELLED가 됩니다. 따라서 위 쿼리에서 구 Context는 **두 조건에 의해 이중으로 제외**됩니다.

- `ct.deleted_at IS NULL` — Core 소프트 삭제
- `st.keyword_status = 'COMPLETED'` — CANCELLED는 통과하지 못함

구 Context의 `keyword_status`가 직전까지 COMPLETED였더라도 수정 트랜잭션이 CANCELLED로 덮으므로, 수정 커밋 시점 이후에는 구 Keyword가 노출되지 않습니다. 커밋 전에는 구 Context가 여전히 유효한 최신 Context이므로 노출되는 것이 정상입니다. 중간 상태가 없다는 것이 단일 트랜잭션 설계의 이점입니다.

**따라서 `ai.context_keyword` Row를 즉시 물리 삭제할 필요가 없습니다.** 삭제 트랜잭션에서 Keyword Row를 지우는 작업을 추가하지 않습니다. 취소 처리 대상은 `context_ai_state`와 `context_embedding`뿐이며, `context_keyword`와 `context_keyword_analysis`는 State에 의해 자연히 차단됩니다. 물리 삭제는 향후 보존 정책에 따른 정리 배치의 몫입니다.

신 Context는 새 `context_id`이므로 아직 Keyword가 없습니다. 이 구간의 응답은 5장의 "AI 미완료" 규칙에 따라 빈 배열입니다.

### 4.3 N+1 방지

Record 목록·Collection 상세·Feed 모두 Record 여러 건을 한 번에 그립니다. Keyword는 `record_id IN (:recordIds)`로 **일괄 조회**한 뒤 애플리케이션에서 `record_id` 기준으로 그룹핑합니다. Record별 조회를 반복하지 않습니다.

Record Keyword와 Collection Keyword는 저장하지 않고 이 조인 집계로 파생합니다. 물리 집계 테이블을 만들지 않습니다.

## 5. AI 미완료 처리

**AI가 미완료이거나 실패한 Context의 Keyword는 빈 배열로 응답합니다. 오류가 아닙니다.**

| 상태 | 응답 |
|---|---|
| `keyword_status = PENDING` | `"keywords": []` |
| `keyword_status = PROCESSING` | `"keywords": []` |
| `keyword_status = FAILED` | `"keywords": []` |
| `keyword_status = CANCELLED` | 해당 Context 자체가 응답 대상 아님 (삭제되었거나 수정으로 교체된 구 Context) |
| 매칭 Keyword가 0개인 COMPLETED | `"keywords": []` |
| `context_ai_state` 행 자체가 없음 | `"keywords": []` |

규칙:

- `null`이 아니라 **빈 배열**입니다. 클라이언트가 분기하지 않아도 되게 합니다.
- HTTP 상태 코드를 바꾸지 않습니다. `200`입니다.
- 마지막 두 행이 중요합니다. `keywords` **배열만으로는** "매칭 Keyword 없음"과 "AI 미완료"가 구분되지 않습니다. 매칭 결과 0건은 오류가 아니라 정상 COMPLETED입니다.
- 이 규칙은 Feed에도 그대로 적용됩니다. AI 미완료 Collection도 Keyword 없이 기본 조회와 Feed 노출이 가능해야 합니다.
- **수정 직후도 같은 케이스입니다.** 신 Context는 새 `context_id`로 PENDING부터 시작하므로 처리가 끝날 때까지 Keyword가 비어 있습니다. 이를 오류로 표현하지 않습니다.

`4.1`의 쿼리는 LEFT JOIN이 아니라 INNER JOIN이므로 미완료 Context는 결과에 아예 나오지 않습니다. 그룹핑 단계에서 `recordIds` 전체를 기준으로 맵을 초기화하고 조회 결과를 채우는 방식으로 구현하면, 결과가 없는 Record가 자연히 빈 리스트가 됩니다.

### 5.1 검색 응답은 판정 상태를 함께 냅니다

**개인 자연어 검색 응답(API 명세 6.1)에 한해 Record 단위 판정 상태 `keywordStatus`를 함께 반환합니다.** `keywords` 배열은 위 표 그대로 두고, 필드를 하나 더합니다.

| `keywordStatus` | 뜻 | 화면이 할 수 있는 것 |
|---|---|---|
| `COMPLETED` | 판정이 끝났습니다. 이 배열이 최종입니다 | 0건이면 "이 기록엔 키워드가 없어요" |
| `PROCESSING` | 아직 처리 중입니다. 기다리면 채워집니다 | "분석 중"이 사실인 유일한 경우 |
| `FAILED` | 처리가 끝내 실패했습니다. 기다려도 오지 않습니다 | 재시도·문의 유도 |

**내부 상태를 그대로 내보내지 않습니다.** `ai.context_ai_state.keyword_status`는 `PENDING`·`PROCESSING`·`COMPLETED`·`FAILED`·`CANCELLED` 다섯 값이지만, 응답은 셋으로 접습니다. 사용자에게 필요한 판단은 **「기다리면 오는가」** 하나이고, `PENDING`과 `PROCESSING`의 차이는 그 판단을 바꾸지 않습니다.

Record 단위 접기 규칙입니다. 한 Record에는 활성 Context가 여럿일 수 있습니다.

```text
활성 Context 중 하나라도 PENDING·PROCESSING   →  PROCESSING
그렇지 않고 하나라도 FAILED                    →  FAILED
그 밖                                          →  COMPLETED
```

- **`PROCESSING`이 `FAILED`를 이깁니다.** 하나가 실패하고 다른 하나가 처리 중이면, 그 처리 중인 것이 끝나면서 Keyword가 더 붙습니다. 그 상황에서 사실인 답은 "기다리면 온다"입니다.
- **`CANCELLED`는 집계에 넣지 않습니다.** 그 Context는 애초에 응답 대상이 아닙니다(위 표 4행). 삭제·교체된 Context 때문에 남아 있는 Context의 상태가 바뀌면 안 됩니다.
- **`context_ai_state` 행이 없으면 `COMPLETED`입니다.** 백엔드가 Context 생성과 같은 트랜잭션에서 `PENDING` 행을 넣으므로(`ContextAiStateRepository`) 정상 경로에서는 나오지 않는 상태입니다. 이것을 `PROCESSING`으로 접으면 **영영 오지 않는 것에 "분석 중"을 띄우게 됩니다.** 이 개정이 없애려는 바로 그 증상입니다.

**`PROCESSING`은 무한히 지속되지 않습니다.** 재스캔이 만료된 `PENDING`·`PROCESSING`을 `FAILED`로 전이시키므로(`ai-rescan-scheduler.md`), 화면의 "분석 중"에는 상한이 있습니다. 이것이 세 값을 그대로 화면 문구에 이어도 되는 근거입니다.

#### 검색 응답에만 넣는 이유

- **소유자 전용 응답이기 때문입니다.** 타인 응답(공개 Collection·책장·타인 Record 카드)에 판정 상태를 실으면 남의 AI 처리 진행 상황이 새어 나갑니다. `2`의 Visibility 원칙과 어긋나며, 타인은 그 기록을 기다릴 이유도 없습니다.
- **증상이 관측된 경로이기 때문입니다.** 방금 만든 기록이 곧바로 뜨는 곳이 검색입니다.
- 다른 응답에 필요해지면 그때 **같은 값 집합으로** 넓힙니다. 여기서 미리 넓히지 않는 것은, 값 집합을 바꾸는 것보다 노출 범위를 넓히는 쪽이 나중에 더 싸기 때문입니다.

#### 하위 호환

**`keywords` 배열의 동작은 하나도 바뀌지 않습니다.** `4.1`의 쿼리도 그대로입니다. `keywordStatus`는 더해지기만 하므로, **필드를 읽지 않는 클라이언트는 개정 전과 완전히 같은 응답을 봅니다.** 이 성질은 응답 조립 테스트로 고정합니다.

### 5.2 이전 판단을 뒤집습니다

직전 판까지 `5`는 다음 두 줄을 규칙으로 두었고, 이 개정이 **그 둘만** 걷어냅니다.

> - 응답에 "AI 처리 중" 같은 상태 필드를 노출하지 않습니다. Keyword 유무만으로 충분하고, 내부 처리 상태는 사용자 관심사가 아닙니다.
> - "매칭 Keyword 없음"과 "AI 미완료"는 응답상 구분되지 않으며, **구분할 필요도 없습니다.**

근거는 *"내부 처리 상태는 사용자 관심사가 아니다"*였고, 그것은 **처리가 짧게 끝난다는 가정** 위에 서 있었습니다. 실측이 그 가정을 벗어났습니다.

```text
S15P11A705-121·-197   GMS 판정이 분당 약 2건만 통과한다 (429)
S15P11A705-198        PROCESSING 잔류 + PROCESSING_EXPIRY_SEC 600초로
                      Context 하나가 10분 얼린 사례
```

그 결과 **세 상황이 화면에서 같아집니다.**

| 실제 | `keywords: []`만 볼 때 | 맞는가 |
|---|---|---|
| 방금 만들어 처리 중 | "키워드 없음" | 기다리면 생기는데 없다고 나옵니다 |
| `FAILED`로 끝남 | "키워드 없음" | 오지 않는데 재시도를 유도할 수 없습니다 |
| 판정 결과 0건 | "키워드 없음" | **맞습니다** |

이전 판단은 **세 번째만 보고** "구분할 필요가 없다"고 정했습니다. 처리 시간이 길어질수록 앞의 둘이 커지고, 실측이 이미 그 지점을 지났습니다.

**뒤집지 않은 것도 적어 둡니다.** 이전 판단이 든 비용 근거, 즉 *"상태 필드를 넣으면 모든 클라이언트가 3상태를 분기해야 한다"*는 **여전히 유효합니다.** 그래서 `null` 대신 빈 배열을 유지하고(`5`의 첫 규칙), 노출을 검색 응답 하나로 좁히고, 필드를 무시하면 개정 전과 같도록 설계했습니다. 분기가 **필요한** 클라이언트는 `PROCESSING` 하나만 보면 됩니다.

관측 경로: [back#136](https://github.com/Team-PinLog/back/issues/136) · front#58(화면 렌더링, 프론트 파트).

## 6. 자연어 검색 결과의 Core 재검증

FastAPI가 반환하는 것은 `recordId`와 `similarity`뿐이며, 이는 `ai` 스키마 기준 결과입니다. **Spring이 Core 기준으로 다시 검증합니다.**

### 6.1 재검증 항목

| 항목 | 확인 |
|---|---|
| 소유권 | `record.member_id = 요청 User` |
| Record 삭제 | `record.deleted_at IS NULL` |
| 활성 Context 존재 | 해당 Record에 `deleted_at IS NULL`인 Context 1건 이상 |
| Place 존재 | `place` 조인 성공 |

```sql
SELECT r.id, r.created_at, p.name, p.address, p.lat, p.lng, p.place_url
FROM core.record r
JOIN core.place p ON p.id = r.place_id
WHERE r.id IN (:recordIds)
  AND r.member_id = :memberId
  AND r.deleted_at IS NULL
  AND EXISTS (
      SELECT 1 FROM core.context ct
      WHERE ct.record_id = r.id AND ct.deleted_at IS NULL
  );
```

### 6.2 재검증이 필요한 이유

- `ai` 스키마의 `is_deleted`는 보조 방어선이며, 삭제 직후 아주 짧은 창에서 Core와 어긋날 수 있습니다.
- `user_id`는 `ai.context_embedding`의 비정규화 값입니다. 검색 범위 필터로는 충분하지만 **인가 판단의 근거로는 부족합니다**. 인가는 Core가 원본입니다.
- FastAPI는 User 인증을 판단하지 않습니다. `userId`를 검색 범위 필터로 신뢰해서 쓸 뿐입니다.

검색 결과의 최종 공개 여부는 Spring이 판단합니다.

### 6.3 결과 조립

- 순서는 FastAPI가 준 `similarity` 내림차순을 유지합니다. Core 재조회가 순서를 바꾸므로 `recordIds` 순서로 다시 정렬합니다.
- 재검증에서 탈락한 Record는 **조용히 제외**합니다. 오류로 만들지 않으며, 부족해진 개수를 다른 Record로 채우지 않습니다.
- 한 Record의 여러 Context가 검색되어도 Record는 한 번만 반환합니다. 중복 제거는 FastAPI가 Record 단위 집계로 수행하지만, Spring도 `recordIds`를 순서를 보존하며 distinct 처리해 이중으로 보장합니다.
- 검색은 본인 데이터 전용이므로 소유자용 DTO를 사용합니다. Context 본문을 포함할 수 있습니다.
- Place 이름 검색과 지도 검색은 이 경로가 아니라 카카오맵 장소 검색 기능으로 별도 유지합니다.

### 6.4 AI 검색 실패 시

FastAPI 호출이 실패하면 검색 결과를 만들 수 없으므로 오류 응답으로 변환합니다. 다만 이 실패는 자연어 검색 기능에 한정되며, Record 저장·조회, Collection 발행, Place 검색은 정상 동작해야 합니다.
