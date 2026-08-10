# BI-13. 타인 조회 공개 범위 필터와 식별자 은닉

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: Jira 작업, [BD-13](../decisions/BD-13-public-boundary-query-dto-split.md), [BD-14](../decisions/BD-14-identifier-concealment.md)

## 산출

- `GET /v1/collections/{id}`가 소유 여부를 판별해 **서로 다른 DTO**를 반환한다 — 소유자는
  `CollectionDetailResponse`, 타인은 `PublicCollectionDetailResponse`/`PublicRecordCardResponse`.
  상속·조건부 직렬화를 쓰지 않는다(BD-13 — 부모에 필드가 추가될 때 조용히 노출되는 것을 막는다).
- **공개 DTO는 Context 본문을 담을 자리가 없다.** `PublicRecordCardResponse.contexts`는 생성자로
  받지 않는 고정 null이다 — 명세 7.3의 wire 계약(`"contexts": null`)은 유지하면서, 실수가 유출이
  아니라 컴파일 오류로 실패하게 만들었다. 공개 조립 경로(`toRecordCardsPublic`)는
  ContextRepository를 호출하지 않는다.
- 공개 진입 검사(데이터모델 5.5): `is_published`·활성(soft delete 제외)·소유자 미탈퇴. 실패는
  403이 아니라 **404**(존재 은닉).
- 공개 제공 필드: Place, keywords(AI 전까지 빈 배열), Record 생성일, addedToCollectionAt.
  작성자 신원(내부 memberId·이메일·닉네임·소셜)은 응답 어디에도 없다(BD-14 — 테스트가 응답
  문자열에서 `memberId`·`email` 등의 부재를 직접 확인).
- follow 상태는 **조회자 자신의 것만**: followed·followId·자기 별칭. 다른 팔로워의 별칭은
  노출되지 않는다(각 조회자별 조회).
- 소프트 삭제 Record는 공개 목록에서 제외(@SQLRestriction + 조립 필터).

## 검증 — 접근 권한표(API 명세 12장) 대응

| 권한표 행 | 테스트 |
| --- | --- |
| 공개 Collection 상세 조회 (본인/팔로우/비팔로우 O) | owner·follower·stranger 각 200 |
| Record Place·Keyword·생성일 조회 (모두 O) | 공개 응답의 place·keywords·createdAt 존재 |
| Context 원문 (본인 O, 타인 X·null) | 소유자 contexts 배열 / 타인 `contexts: null` + 본문 문자열 부재 |
| Collection 제목·구성 수정 (소유자만) | 팔로워 PATCH·record 추가 404 |
| Context 수정·삭제 (소유자만) | 팔로워 PATCH context 404, 타인 Record 상세 404 |
| Follow 별칭 조회 (지정한 본인만) | A의 별칭이 B 응답에 없음 |

추가: 작성자 탈퇴 404, 미발행(is_published=false) 404, 소프트 삭제 Record 제외.

`PublicCollectionApiTests` 9건 + `./gradlew clean check --no-daemon` 통과.
