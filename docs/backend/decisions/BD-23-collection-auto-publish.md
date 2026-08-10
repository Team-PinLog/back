# BD-23. Collection은 생성 즉시 자동 발행 — `is_published` 컬럼은 유지한다

- **상태**: Accepted
- **날짜**: 2026-07-22 (정책·데이터 모델 확립 시점. 단일 커밋으로 특정 불가)
- **작성 시점**: 2026-07-27 — 결정 이후에 정리
- **관련**: Jira 작업
- **공용 계약**: [02_정책_정의서 §7](https://github.com/Team-PinLog/docs/blob/main/static/02_정책_정의서.md) · [06_데이터모델_및_무결성 §2.6](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md) · [10_MVP_기능범위 §2](https://github.com/Team-PinLog/docs/blob/main/static/10_MVP_기능범위.md)

## 맥락

Collection은 생성 즉시 공개된다. MVP에서 비공개 전환과 발행 취소는 제공하지 않는다. 이 전제 아래 `is_published` 컬럼을 둘지 말지가 갈렸다.

컬럼을 두지 않으면 "모든 활성 Collection은 공개"라는 규칙이 코드에 암묵적으로 박힌다. 두면 항상 `true`인 컬럼이 생긴다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) 컬럼 없이 "활성 = 공개"로 둔다 | 항상 참인 값을 저장하지 않는다. 쿼리에 무의미한 필터가 붙지 않는다 | 나중에 비공개를 도입하면 마이그레이션과 함께 Feed·Shelf·Collection 상세 세 경로의 쿼리를 모두 고쳐야 한다 |
| **(b) `is_published BOOLEAN NOT NULL DEFAULT TRUE`를 두고 항상 `true`로 생성** | 공개 여부가 데이터로 표현된다. 도입 시 컬럼 추가 없이 값과 필터만 다루면 된다 | 항상 참인 컬럼과 항상 통과하는 필터가 생긴다 |

## 결정

**(b)를 채택한다.** 자동 발행 자체는 **제약으로 주어졌고**([10_MVP_기능범위](https://github.com/Team-PinLog/docs/blob/main/static/10_MVP_기능범위.md)가 비공개 전환·발행 취소를 명시적으로 제외), 컬럼 유지는 **능동적 선택**이다.

`published_at`도 함께 둔다. Feed 후보 스캔 인덱스가 `(is_published, published_at DESC) WHERE deleted_at IS NULL`이므로, 컬럼이 없으면 이 인덱스 형태 자체가 성립하지 않는다.

발행 중에도 제목과 Record 구성은 수정할 수 있고 변경은 공개 Collection에 즉시 반영된다. 별도 스냅샷을 만들지 않는다.

## 결과

**감수하는 것**

- **죽은 값처럼 보인다.** `is_published`가 항상 `true`라 신규 참여자가 "왜 있지?"라고 묻게 된다. 이 문서가 그 답이다.
- **무의미한 필터** — 모든 공개 조회에 `is_published = true`가 붙지만 지금은 아무것도 걸러내지 않는다. 그럼에도 붙여둔다. 비공개가 도입되는 순간 빠뜨린 곳이 곧 유출 지점이 되기 때문이다.
- **되돌릴 수 없는 공개** — 사용자가 Collection을 만들면 즉시 타인에게 보인다. 실수로 만든 Collection을 비공개로 돌릴 방법이 없고, 삭제만 가능하며 복구도 없다([BD-08](BD-08-soft-delete-no-restore.md)).

**재검토 트리거**

- 비공개 전환을 도입하면 세 가지를 함께 결정해야 한다.
  1. Feed·타인 Shelf 조회·Collection 상세 **세 경로 모두**에 필터가 실제로 걸려 있는지 확인([BD-13](BD-13-public-boundary-query-dto-split.md)의 경로 통합이 여기서 값을 한다)
  2. `record_count`의 정의 — 전체 연결 수인가 공개분만인가([BD-20](BD-20-selective-denormalization.md))
  3. 이미 발행된 Collection을 비공개로 돌릴 때 팔로워의 Library에서 어떻게 사라지는가
