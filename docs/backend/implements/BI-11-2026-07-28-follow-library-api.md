# BI-11. Follow와 Shelf 기반 Library 조회 API

- **상태**: ✅ 완료
- **날짜**: 2026-07-28
- **관련**: S15P11A705-69, [BD-15](../decisions/BD-15-shelf-not-a-table.md), [BD-14](../decisions/BD-14-identifier-concealment.md)

## 산출

- `POST /v1/follows` — 진입점은 body의 `collectionId`(식별자 은닉). 활성·발행 Collection이고 작성자가
  활성 회원일 때만 작성자(followee)를 식별한다. 자기 Shelf는 422 `SELF_FOLLOW_NOT_ALLOWED`,
  중복은 409 `DUPLICATE_FOLLOW`(ErrorCode 신설), 부적격 Collection은 404 은닉. 201
  `{followId, alias: null, createdAt}` — 생성 시 별칭은 없다(명세 8.2).
- `GET /v1/follows` — 내 팔로우 목록, `created_at DESC` 커서. **followee가 탈퇴한 행은 Member entity
  join으로 제외**한다 — Member의 `@SQLRestriction`이 join에도 적용되어 Library 정의(데이터모델 1.4의
  `m.deleted_at IS NULL` join)와 같은 결과를 낸다.
- `GET /v1/follows/{followId}/collections` — 내 Follow가 아니면 404. followee 탈퇴 시 404가 아니라
  **빈 목록**("목록에서 제외" 규칙). 발행·활성 Collection만 `created_at DESC` 커서로 반환하고,
  items에는 작성자 신원 없이 `keywords: []`를 포함한다(8.1과 정합).
- `PATCH /v1/follows/{followId}` — 별칭 strip 후 빈 값은 null(제거), 20자 초과 400, 중복 허용.
  정규화는 서비스가 담당하므로 DTO에는 검증을 걸지 않았다(null=제거 의미 보존).
- `DELETE /v1/follows/{followId}` — softDelete, 204. 부분 유니크(uq_follow_active) 덕에 해제 후
  재팔로우가 가능하다.

## 검증

- `FollowApiTests` 12건 — 생성 201·alias null / 자기 422 / 중복 409·행 미증가 / 목록 커서 /
  책장 Collection 발행만·삭제 제외·커서 / followee 탈퇴 시 두 목록 모두 제외 / 별칭 trim·빈 값
  null 저장(DB 확인)·21자 400·중복 허용 / 해제 204·목록 제외 / 남의 followId 404(조회·해제).
- `./gradlew clean check --no-daemon` 통과.

## 참고

- Spring Framework 7에서 `HttpStatus.UNPROCESSABLE_ENTITY`는 deprecated — RFC 9110 명칭인
  `UNPROCESSABLE_CONTENT`(422)를 사용했다.
