# BD-13. 식별자 은닉 — `member.id` 비공개, `public_id` 없이 Collection id를 진입점으로

- **상태**: Accepted
- **날짜**: 2026-07-22 (데이터 모델 확립 시점. 단일 커밋으로 특정 불가)
- **작성 시점**: 2026-07-27 — 결정 이후에 정리
- **관련**: S15P11A705-76
- **공용 계약**: [06_데이터모델_및_무결성 §5.3](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md) · [08_API_명세 §1.1](https://github.com/Team-PinLog/docs/blob/main/static/08_API_명세.md)

## 맥락

PinLog는 익명 서비스다. 실명·닉네임·소셜 계정 같은 신원 정보를 타인에게 공개하지 않는다. 그런데 타인의 Shelf를 팔로우하고 그 Collection을 보려면 **어떤 형태로든 "그 사용자"를 가리키는 값**이 오가야 한다.

`member.id`는 `BIGINT IDENTITY` 순차 값이다([BD-09](BD-09-integrity-in-database.md)). 이걸 그대로 노출하면 두 가지가 샌다.

- **열거** — 1, 2, 3… 을 훑어 전체 사용자를 긁을 수 있다.
- **가입 순번** — 작은 값이 초기 사용자다. 소규모 서비스에서는 그것만으로 신원 추론의 단서가 된다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) `member.id`를 그대로 공개 | 구현이 없다 | 열거 가능. 가입 순번 노출 |
| (b) `member.public_id UUID`를 추가해 공개 | 열거·순번 문제가 사라짐. Shelf 전용 URL이 가능 | 컬럼·인덱스가 늘고 모든 공개 응답에 매핑 계층이 필요. MVP에 그 URL 요구가 없다 |
| **(c) 사용자 식별자를 공개하지 않고 Collection id를 진입점으로 쓴다** | 추가 컬럼 없이 열거·순번을 동시에 막는다 | Shelf 자체를 가리키는 URL을 만들 수 없다 |

## 결정

**(c)를 채택한다. 능동적 선택.**

- `member.id`는 **공개 응답(타인 조회)에 포함하지 않는다.** 예외는 로그인·가입 확정 응답에서 본인의 `memberId`를 받는 것뿐이다.
- 별도 공개 식별자를 두지 않는다. 대신 **Collection id를 진입점**으로 삼는다. Collection id는 발행 시 공개 대상이므로 노출되어도 정책 위반이 아니다. 사용자 열거와는 성격이 다르다.

```text
POST /follows (body: collectionId)          -- 서버가 소유자를 역조회해 처리
GET  /feed/collections/{collectionId}/shelf -- 같은 선반의 다른 공개 Collection
GET  /follows                               -- 서버가 follow 테이블로 조회
```

- **개인 API는 사용자 ID를 Query나 Body로 받지 않는다.** 서버가 토큰으로 식별한다([BD-20](BD-20-auth-token-model.md)). 파라미터로 받으면 값을 바꿔 남의 데이터를 요청하는 경로가 열린다.

## 결과

**감수하는 것**

- **Shelf 전용 URL을 만들 수 없다.** 새로고침하거나 공유할 수 있는 "이 사람의 선반" 페이지가 없다. Shelf에 도달하려면 항상 Collection을 거쳐야 한다. Shelf가 물리 테이블도 아니라는 점과 맞물린다([BD-14](BD-14-shelf-not-a-table.md)).
- **서버 역조회가 늘어난다.** 팔로우 생성이 `collectionId`를 받아 소유자를 찾는 단계를 거친다.
- **Collection이 삭제되면 진입점이 사라진다.** 팔로우한 Shelf에 접근하던 경로가 그 Collection에 묶여 있었다면 다른 Collection을 통해 다시 들어가야 한다.

**재검토 트리거**

- Shelf 전용 URL(공유·북마크 가능한 페이지)이 필요해지면 → 그때 `member.public_id UUID`를 추가한다. 기존 행은 일괄 `UPDATE`로 채울 수 있으므로 지금 미리 만들 이유가 없다. 이것이 (b)를 지금 버린 근거다.
