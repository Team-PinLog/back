# BD-14. Shelf·Library를 물리 테이블로 두지 않는다

- **상태**: Accepted
- **날짜**: 2026-07-22 (데이터 모델 확립 시점. 단일 커밋으로 특정 불가)
- **기록**: 소급 (2026-07-27 작성)
- **관련**: S15P11A705-76
- **공용 계약**: [06_데이터모델_및_무결성 §1.4](https://github.com/Team-PinLog/docs/blob/main/static/06_데이터모델_및_무결성.md) · [03_공식_용어사전](https://github.com/Team-PinLog/docs/blob/main/static/03_공식_용어사전.md)

## 맥락

용어사전에 Shelf와 Library가 도메인 개념으로 정의되어 있다. Shelf는 한 User가 발행한 Collection 목록이고, Library는 내 Shelf와 팔로우한 Shelf를 모은 개인 공간이다. 도메인 용어가 있으니 테이블도 만들 것인가가 질문이었다.

살펴보면 Shelf에는 **고유한 속성이 하나도 없다.**

| Shelf의 구성요소 | 실제 위치 |
|---|---|
| 구분 기준 | `collection.member_id` |
| 표시 이름 | `follow.display_name` (Shelf가 아니라 **팔로우 관계**에 속한다) |
| 내용물 | Collection 목록 |

User와 1:1이면서 자기 것이 없다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) `shelf` 테이블을 만들고 `collection.shelf_id`로 참조 | 도메인 용어와 테이블이 1:1로 대응 | 값이 없는 행이 User마다 하나씩 생긴다. `collection`이 `member_id`와 `shelf_id`를 둘 다 갖거나, `shelf`를 거쳐야만 소유자를 알 수 있다 |
| **(b) 테이블 없이 조회 결과의 그룹핑 단위로 둔다** | 빈 테이블·불필요한 조인이 없다. `follow`가 `member`를 직접 참조할 수 있다 | 도메인 용어와 물리 모델이 어긋난다. Shelf를 얻으려면 항상 그룹핑 쿼리가 필요하다 |

## 결정

**(b)를 채택한다. 능동적 선택.** `collection.member_id`로 그룹핑한 결과가 Shelf이고, 그 묶음이 Library다.

```sql
-- 내 선반
SELECT * FROM collection WHERE member_id = :me AND deleted_at IS NULL;

-- 라이브러리(팔로우한 선반들)
SELECT f.followee_member_id AS shelf_key, f.display_name, c.*
FROM follow f
JOIN member m     ON m.id = f.followee_member_id AND m.deleted_at IS NULL
JOIN collection c ON c.member_id = f.followee_member_id AND c.deleted_at IS NULL
WHERE f.follower_member_id = :me AND f.deleted_at IS NULL;
```

**부수 효과가 하나 있다.** Shelf 테이블이 없으니 `follow`가 팔로우 대상을 `member`로 직접 참조한다. 덕분에 자기 팔로우 금지를 DB `CHECK (followee_member_id <> follower_member_id)`로 보장할 수 있다([BD-09](BD-09-integrity-in-database.md)). Shelf를 거쳤다면 자기 Shelf인지 판정에 조인이 필요해 `CHECK`로 표현되지 않았을 것이다.

`display_name`이 Shelf가 아니라 팔로우 관계에 속하는 것도 이 모델의 귀결이다. 같은 Shelf라도 팔로워마다 다른 이름을 붙일 수 있다.

## 결과

**감수하는 것**

- **도메인 용어와 물리 모델이 어긋난다.** 코드에서 Shelf를 다루지만 대응하는 엔티티가 없다. 신규 참여자가 `shelf` 테이블을 찾다가 헤맬 수 있어 [용어사전](https://github.com/Team-PinLog/docs/blob/main/static/03_공식_용어사전.md)과 이 문서가 그 간극을 메운다.
- **Shelf 단독 조회가 없다.** 항상 Collection 목록을 그룹핑해 얻는다. 팔로우한 Shelf가 많아지면 `follow (follower_member_id, created_at DESC)` 인덱스에 의존한다.
- **Shelf 전용 URL이 없다**는 제약과 맞물린다([BD-13](BD-13-identifier-concealment.md)). 가리킬 행도 없고 공개 식별자도 없다.
- **다단 삭제 확인이 필요하다.** Library 조회는 `member`·`collection`·`collection_record`·`record`의 삭제 상태를 모두 확인해야 한다([BD-07](BD-07-soft-delete-no-restore.md)).

**재검토 트리거**

- Shelf에 고유 속성이 생기면(소개글, 커버 이미지, 공개 설정) → 그때 테이블을 만든다. 속성이 하나라도 생기는 순간 이 결정의 전제가 사라진다.
