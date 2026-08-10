# BI-17. 별칭 수정 요청의 키 생략 동작을 계약으로 고정

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: Jira 작업, [docs#20](https://github.com/Team-PinLog/docs/pull/20)

## 증상

`PATCH /v1/follows/{followId}`에 `{}`처럼 `alias` 키를 생략해 보내면 **기존 별칭이 지워진다.**

`FollowAliasUpdateRequest`가 컴포넌트 하나짜리 record라 Jackson이 "키 없음"과 "명시적 null"을 똑같이 `null`로 역직렬화하고, `FollowService.changeAlias`가 그 `null`을 API 명세 8.3이 정한 "제거"로 해석한다.

명세 8.3은 **명시적 `null`만** 제거로 정의하고 키 생략은 정의하지 않았다. 즉 정의되지 않은 입력이 사용자 데이터를 지우는 상태였다.

## 결정: 동작을 바꾸지 않고 계약으로 고정한다

두 안을 놓고 후자를 골랐다.

| 안 | 내용 | 비용 |
|---|---|---|
| 구분한다 | `JsonNullable` 등으로 "부재"와 "null"을 갈라, 부재는 미변경 | DTO에 장치 도입. 부분 수정이 실제로 필요한 리소스가 아직 없는데 모든 요청 DTO가 그 비용을 진다 |
| **구분하지 않는다 (채택)** | 현행 유지 + 계약 명시 | 변경된 필드만 모아 보내는 클라이언트가 별칭을 잃을 수 있다 |

Follow에서 **수정 가능한 필드가 `alias` 하나뿐**이라 부분 수정 요청이 나올 이유가 없다. 필드가 여러 개인 PATCH가 등장하면 그때 도입하며, 그 신호를 규약에 적어 뒀다.

## 산출

**운영 코드 변경 없음.**

- `FollowApiTests.aliasSetRemoveAndKeyOmissionAllBehaveAsContracted` — 네 단계를 한 흐름으로 고정한다. 설정 → 명시적 null 제거 → 재설정 → **`{}` 제거**. 각 단계에서 응답 `alias`와 `core.follow.display_name` 실제 값을 함께 단정한다.
- `docs/development/api-conventions.md` — "요청 본문에서 키를 생략한 것과 명시적 `null`을 구분하지 않는다"를 요청 모델 규약으로 추가하고, 구분이 필요해질 때의 재검토 신호(필드 여러 개인 PATCH 등장)를 함께 적었다.
- 정본 `08 §8.3` — `{}`도 제거임을 명시하고, 변경된 필드만 보내는 방식의 주의를 인용으로 붙였다([docs#20](https://github.com/Team-PinLog/docs/pull/20)).

## 검증 — 고정 테스트에는 자연스러운 RED가 없다

동작이 이미 그렇게 되어 있으므로 테스트는 처음부터 통과한다. **그래서 통과만으로는 이 테스트가 무언가를 고정한다는 증거가 되지 않는다.** 기각한 대안을 실제로 넣어 보고 잡히는지 확인했다(`Cursor.decode`를 뮤테이션 검사로 고정한 Jira 작업와 같은 방식).

`FollowService.changeAlias`에 "null이면 미변경"을 임시로 넣고 실행:

```
FollowApiTests > aliasSetRemoveAndKeyOmissionAllBehaveAsContracted() FAILED
java.lang.AssertionError: JSON path "$.data.alias"
Expected: null
     but: was "취향 좋은 카페"
```

기각한 대안이 정확히 잡혔다. 확인 후 운영 코드를 원복했으며, 이 커밋에는 테스트와 문서만 들어 있다.

- `FollowApiTests` 14건 통과.
- `./gradlew clean check --no-daemon` 통과.

## 감수하는 것

변경된 필드만 모아 보내는 클라이언트가 `alias`를 빠뜨리면 별칭이 지워진다. 지금은 그런 요청이 나올 구조가 아니지만(필드가 하나), **계약으로 적어 두는 것 말고 막는 장치는 없다.** 프론트 주의사항은 정본 8.3의 인용 블록에 있다.
