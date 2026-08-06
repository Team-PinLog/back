# 검색 응답에 Keyword 판정 상태 노출 (S15P11A705-209)

- **상태**: ✅ 완료
- **관련**: [back#136](https://github.com/Team-PinLog/back/issues/136)(정본) · front#58(화면 렌더링, 프론트 파트) · S15P11A705-209
- **PR**: back#161(명세 개정, 1/3) · [Team-PinLog/docs#41](https://github.com/Team-PinLog/docs/pull/41)(공용 계약, 2/3) · back(구현, 3/3)

## 무엇을 만들었나

`POST /v1/search/records` 응답 항목에 Record 단위 판정 상태 `keywordStatus`(`COMPLETED`·`PROCESSING`·`FAILED`)를 더했다. `keywords: []` 하나로는 「아직 처리 중」·「처리 실패」·「판정 0건」이 구분되지 않아, 0건으로 완료된 기록에 화면이 "AI가 분석 중이에요"를 영구히 띄우던 문제를 없앴다.

## 구현보다 명세 개정이 먼저였다

**이 작업의 첫 절반은 코드가 아니다.** 상태 노출을 금지하고 있던 것이 우리 명세였다.

> `docs/ai/spec/ai-response-assembly.md` §5 (개정 전)
> - 응답에 "AI 처리 중" 같은 상태 필드를 노출하지 않습니다.
> - "매칭 Keyword 없음"과 "AI 미완료"는 응답상 구분되지 않으며, **구분할 필요도 없습니다.**

back#136에서 백엔드가 이 조항을 근거로 **「백엔드는 계약대로다」**라고 판정했고 **문서 기준으로 그 판정이 맞았다.** 그래서 순서가 이렇게 됐다.

```
1/3  back#161   응답 조립 명세 §5 개정 — 왜 뒤집는가
2/3  docs#41    08 API 명세에 필드 반영 — 공용 계약
3/3  (이 리포트) 구현 + 테스트
```

2/3이 3/3보다 먼저인 것은 백엔드 요청이다(back#136). `SearchRecordsResponse`는 프론트에 노출되는 공용 계약이라, 구현 전에 계약 문서에 올라와야 응답 직렬화 테스트를 그 문서 근거로 고정할 수 있다.

### 뒤집은 근거

§5의 판단은 *"내부 처리 상태는 사용자 관심사가 아니다"*였고 이는 **처리가 짧게 끝난다는 가정** 위에 있었다. 실측이 그 가정을 벗어났다.

```text
S15P11A705-121·-197   GMS 판정이 분당 약 2건만 통과한다 (429)
S15P11A705-198        PROCESSING 잔류 + PROCESSING_EXPIRY_SEC 600초로
                      Context 하나가 10분 얼린 사례
```

§5는 세 상황 중 「판정 0건」 하나만 보고 「구분 불필요」로 정했고, 처리 시간이 길어질수록 나머지 둘이 커진다.

### 뒤집지 않은 것

§5가 든 **비용 근거**, 즉 *"상태 필드를 넣으면 모든 클라이언트가 3상태를 분기해야 한다"*는 여전히 유효하다고 보고 유지했다.

| 유지 | 방법 |
|---|---|
| 빈 배열을 `null`로 바꾸지 않는다 | `keywords` 계약 무변경 |
| 3상태 분기를 강제하지 않는다 | 필드를 무시하면 개정 전과 동일. 분기가 필요하면 `PROCESSING` 하나만 |
| 내부 상태를 그대로 내보내지 않는다 | 5값 → 3값으로 접는다 |
| 노출 범위를 넓히지 않는다 | 검색 응답에만. 타인 응답에는 없다 |

## 핵심 결정 — 기존 세 쿼리를 한 글자도 바꾸지 않는다

**이 티켓의 주 리스크는 하위 호환이고, 그것이 걸리는 지점은 한 곳뿐이다.** `ContextKeywordRepository`의 기존 쿼리들이다.

합칠 수 없는 이유는 구조다. 기존 쿼리는 `st.keyword_status = 'COMPLETED'` **INNER JOIN**이라 **미완료 Context를 애초에 만나지 못한다.** 상태를 알아내려면 걸러내지 않은 집합이 필요한데, 그러자고 조건을 풀면 `keywords` 배열의 계약이 바뀐다.

```text
✗  기존 쿼리의 COMPLETED 조건을 풀어 상태도 함께 집계    keywords 계약이 바뀐다
✓  LEFT JOIN 집계를 별도 쿼리로 두고 배열 쪽은 무변경    쿼리 1회 증가
```

**쿼리 1회를 더 내는 쪽을 골랐다.** 하위 호환 위험을 코드 구조 자체로 없애는 값이 왕복 1회보다 크다. 실제로 `keywords`를 만드는 경로에는 이 PR의 변경이 **한 줄도 닿지 않는다.**

### 새 쿼리

```sql
SELECT ct.record_id AS record_id,
    CASE
        WHEN bool_or(st.keyword_status IN ('PENDING', 'PROCESSING')) THEN 'PROCESSING'
        WHEN bool_or(st.keyword_status = 'FAILED')                   THEN 'FAILED'
        ELSE 'COMPLETED'
    END AS keyword_status
FROM core.context ct
LEFT JOIN ai.context_ai_state st ON st.context_id = ct.id
WHERE ct.record_id IN (:recordIds)
    AND ct.member_id = :memberId
    AND ct.deleted_at IS NULL
GROUP BY ct.record_id
```

`LEFT JOIN`인 것이 요점이다. `context_ai_state` 행이 없는 Context도 집계에 남아야 한다. INNER JOIN이면 그런 Record가 결과에서 통째로 빠져 호출부가 상태를 못 받는다.

`CASE`의 **분기 순서가 곧 명세 5.1의 접기 규칙**이다.

## 접기 규칙의 근거 셋

### `PROCESSING`이 `FAILED`를 이긴다

한 Record에 실패한 Context와 처리 중인 Context가 함께 있으면 `PROCESSING`이다. 그 처리 중인 것이 끝나며 Keyword가 **더 붙기** 때문에, 그 상황에서 사실인 답은 "기다리면 온다"다. 반대로 접으면 아직 올 것이 있는데 재시도를 권하게 된다.

`recordStillProcessingOneContextIsProcessingEvenIfAnotherFailed()`가 이것을 고정한다. 이 단언이 없으면 `CASE`의 두 분기를 맞바꿔도 나머지 테스트가 전부 통과한다.

### `CANCELLED`는 집계에 넣지 않는다

두 `bool_or` 어디에도 걸리지 않아 자연히 `COMPLETED` 쪽으로 떨어진다. 의도한 결과다. 그 Context는 삭제·교체되어 애초에 응답 대상이 아니므로(명세 5장), 그것 때문에 **살아 있는 Context의 상태가 바뀌면 안 된다.**

테스트에서 `deleted_at`을 일부러 채우지 않았다. `deleted_at`을 채우면 그 Context가 `deleted_at` 조건에 걸려 집계에서 빠지므로, `CANCELLED` **상태값 처리 자체를 확인할 수 없다.** 두 방어선(`deleted_at` 조건과 상태값 처리) 중 하나만 남겨, 남은 그 하나가 일하는지를 본다.

### 상태 행이 없으면 `COMPLETED`다

`PROCESSING`으로 접는 쪽이 직관적으로 보이지만, 그러면 **영영 오지 않는 것에 "분석 중"을 띄우게 되어 이 티켓이 없애려는 증상이 그대로 재발한다.**

정상 경로에서는 나오지 않는 상태라는 근거는 `ContextAiStateRepository`가 Context 생성과 같은 트랜잭션에서 `PENDING`을 넣는다는 것이다. 그래서 이 단언은 "있을 수 있는 상태"가 아니라 **어긋난 데이터가 화면을 망가뜨리지 않는지**를 본다.

## 조립 단계에서 두 값을 서로 맞추지 않는다

`keywords`와 `keywordStatus`는 **다른 쿼리에서 오고, 그 사이에 판정이 끝날 수 있다.** `PROCESSING`인데 배열이 차 있거나 그 반대인 조합이 정상적으로 나온다.

여기서 "맞춰" 주면 **둘 중 하나를 조용히 거짓으로 만드는** 셈이고, 어느 쪽을 고쳐도 사용자에게는 그 순간 사실이던 값이 사라진다. `@Transactional`을 붙이지 않은 기존 판단(FastAPI 호출 5s 동안 커넥션을 잡지 않는다)과 같은 방향이다. 검색은 움직이는 대상을 최선으로 재검증하는 일이라, 한 스냅샷으로 묶는다고 더 정확해지지 않는다.

## 변경한 파일

| 파일 | 무엇을·왜 |
|---|---|
| [`KeywordResponseStatus`](../../../src/main/java/com/pinlog/pinlogback/domain/ai/KeywordResponseStatus.java) | 응답용 3값 enum 신설. 이름을 `KeywordStatus`로 두지 않은 이유는 **내부 5값과 값 집합이 다르기 때문**이다. 같은 이름이 다른 집합을 뜻하면 어느 쪽 집합인지를 매번 확인해야 한다. `domain/search`가 아니라 `domain/ai`에 둔 것은 값의 출처가 `ai` 스키마이고, 명세 5.1이 나중에 다른 응답으로 넓힐 수 있다고 적었기 때문이다 |
| [`ContextKeywordRepository`](../../../src/main/java/com/pinlog/pinlogback/domain/ai/repository/ContextKeywordRepository.java) | `findKeywordStatusForOwner` 신설. **기존 세 쿼리는 무변경.** `ct.member_id` 방어 조건은 기존 소유자용 쿼리와 같은 이유다 |
| [`RecordSearchItemResponse`](../../../src/main/java/com/pinlog/pinlogback/domain/search/dto/RecordSearchItemResponse.java) | `keywordStatus` 필드 추가. 위치는 08 §6.1 예시와 같게 `keywords` 뒤·`createdAt` 앞 |
| [`RecordSearchService`](../../../src/main/java/com/pinlog/pinlogback/domain/search/service/RecordSearchService.java) | 상태 조회를 붙이고 조립에 실었다. 상태가 없으면 `COMPLETED`로 채운다. 클래스 javadoc의 "DB 조회 셋"을 "넷"으로, 조립 설명에 판정 상태를 더해 문서와 코드가 어긋나지 않게 했다 |

## 검증

### 계약 단언 8건 — `RecordSearchApiTests`

| 테스트 | 무엇을 막는가 |
|---|---|
| `judgementThatFinishedIsCompletedEvenWithNoKeyword` | **back#136의 증상 자체.** 0건 완료가 "분석 중"으로 보이던 조합 |
| `bothPendingAndProcessingSurfaceAsProcessing` | 내부 값이 그대로 새어 클라이언트가 불필요한 구분을 하게 되는 것 |
| `failedJudgementIsReportedSoTheUserCanRetry` | 실패가 "키워드 없음"에 묻히는 것 |
| `recordStillProcessingOneContextIsProcessingEvenIfAnotherFailed` | `CASE` 두 분기를 맞바꾸는 회귀 |
| `cancelledContextDoesNotDragTheRecordStatus` | 삭제·교체된 Context가 살아 있는 것의 상태를 끄는 것 |
| `recordWithNoAiStateRowIsCompletedRatherThanForeverProcessing` | 어긋난 데이터에서 증상이 재발하는 것 |
| `anotherMembersContextDoesNotDecideMyRecordStatus` | `ct.member_id` 방어선이 빠지는 것 |
| `addingTheStatusFieldChangesNothingAboutTheKeywordsArray` | **하위 호환.** 아래 참고 |

### 하위 호환 — 구조가 아니라 실행으로 확인했다

구조적 근거는 "기존 쿼리를 안 바꿨다"이지만 그것은 **코드 읽기**다. 그래서 미완료 Context가 섞인 Record에서 `keywords` 배열이 그대로인지를 실행으로 붙잡았다. 상태 조회가 기존 조회에 조건을 흘려보내면 여기서 드러난다. `PRIVATE_ONLY` 포함·`BLOCKED` 제외까지 함께 단언해 가시성 필터가 그대로임도 본다.

**필드를 무시하면 개정 전과 같다**는 주장의 나머지 절반은 기존 36건이 그대로 통과한다는 사실이다. 그 36건 중 어느 것도 `keywordStatus`를 보지 않는다.

### N+1 부재 — 측정으로 확인했다

`RecordSearchQueryCountTests`를 신설했다(`SqlQueryCounter` 사용, S15P11A705-252 선례). 결과가 3→12건으로 늘어도 **상태 조회는 1회 고정**이고, **기존 Keyword 조회 횟수도 그대로**다. 후자가 "필드를 더하면서 기존 조회를 늘리지 않았다"가 측정으로 드러나는 유일한 자리다.

결과 0건이면 상태 조회 자체가 나가지 않는 것도 함께 고정했다. 빈 `IN ()`으로 도는 쿼리는 문법 오류이거나 전체 스캔이기 때문이다.

`RecordSearchApiTests`와 클래스를 나눈 이유는 `@Import`가 Spring 컨텍스트 캐시 키를 바꿔 **컨텍스트를 하나 더 띄우기** 때문이다.

### 실행 결과

```text
./gradlew test --tests '*RecordSearchApiTests' --tests '*RecordSearchQueryCountTests'
  RecordSearchApiTests        36건 (신규 8 포함) 통과
  RecordSearchQueryCountTests  2건 통과
./gradlew clean check   통과 (coverage 게이트 포함)
```

## 남은 것

- **프론트 화면은 이 시리즈 범위 밖이다**(front#58). 필드를 주는 데까지가 AI 파트 몫이다. 다만 **현재의 "AI가 분석 중" 문구는 `keywordStatus == "PROCESSING"`일 때만 맞다.** 프론트가 그 조건을 걸지 않으면 필드가 와도 증상이 남는다.
- **다른 응답에는 상태가 없다.** 필요해지면 **같은 값 집합으로** 넓힌다(명세 5.1). 미리 넓히지 않은 것은 값 집합을 바꾸는 것보다 노출 범위를 넓히는 쪽이 나중에 더 싸기 때문이다.
- **`docs/ai/spec/ai-response-assembly.md`의 머리말이 낡았다.** *"현재 코드가 없는 구현 예정 명세입니다"*로 시작하지만 §4·§6은 이미 구현돼 있고 이제 §5.1도 그렇다. `docs/ai/README.md`에도 같은 문구가 있다. 이 티켓 범위 밖이라 손대지 않았다.
