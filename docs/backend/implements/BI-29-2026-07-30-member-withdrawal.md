# BI-29. 회원 탈퇴

- **상태**: ✅ 완료
- **날짜**: 2026-07-30
- **관련**: S15P11A705-65, [back#34](https://github.com/Team-PinLog/back/issues/34),
  [BD-41](../decisions/BD-41-withdrawn-member-check-in-authentication-filter.md)(Access 창을 필터에서 닫은 결정),
  [BD-37](../decisions/BD-37-ai-derived-invalidation-inside-deletion-transaction.md)(AI 무효화를 삭제 트랜잭션 안에),
  [BI-23](BI-23-2026-07-29-ai-derived-invalidation-on-delete.md)(나머지 삭제 경로 셋)

## 산출

- `DELETE /api/core/v1/me` — `MeController`. 204, 본문 없음, 쿠키 3종 만료.
- `MemberWithdrawalService` — `06 §6.9`의 순서를 한 `@Transactional`에 담는다.
- `SocialAccount.withdraw()` — `withdrawn:<id>` 치환과 `deleted_at`을 한 UPDATE에.
- `JwtAuthenticationFilter` — 탈퇴 회원 판정 추가(BD-41).
- 리포지토리 finder 5개 — 회원 단위 조회 넷과 Follow 양방향 조회 하나.

## 이 공백이 붙잡고 있던 것

탈퇴 경로가 없어서 **완료된 작업 둘이 미충족 상태였다.**

- `S15P11A705-124`가 요구한 AI 파생 무효화 네 지점 중 **탈퇴만 비어 있었다.** [#80](https://github.com/Team-PinLog/back/pull/80)이 나머지 셋에 적용했지만 붙일 서비스가 없어 제외했다.
- `S15P11A705-147`이 미탈퇴 판정을 `MemberRepository.isActive` 하나로 모아 뒀는데, **`member.deleted_at`을 세팅하는 주체가 없어 그 코드가 한 번도 발동하지 않았다.** 이제 공개 조회 세 경로(Collection 상세 404·팔로우 404·팔로우한 책장 빈 목록)가 새 코드 없이 동작한다.

## 이슈 본문에서 따르지 않은 것

`back#34`은 2026-07-27 작성이고 그 뒤로 전제가 바뀌었다.

| 본문 | 실제 |
|---|---|
| `this.email = null;` | **`NOT NULL` 위반.** `V6`(#103) 이후 넣을 수 없다. 마스킹은 치환이며 `NULL`이 아니다(06 §2.2) |
| `"deleted:" + id`가 *"부분 유니크 충돌을 피할 유일값"* | 유일값이 **불필요하다.** 유니크가 활성행만 대상이라 마스킹 시점엔 이미 인덱스 밖이다. id는 운영 추적 편의다 |
| 연쇄 삭제·AI 무효화가 *"범위 밖"* | 테이블이 모두 들어왔고 `AiDerivedDataRepository`도 `dev`에 있다 — **이번 범위 안이다** |

`repository.delete()`를 쓰지 말라는 경고는 유효하다. `@SQLDelete`가 도는 시점에 Hibernate가 엔티티를 removed로 보아 마스킹 UPDATE가 flush되지 않고, `@SQLRestriction` 때문에 다시 조회해 고칠 수도 없다.

## 설계 판단 넷

**① 행 잠금을 쓰지 않는다.** `RecordDeletionService.cascadeDelete`가 Record·Collection을 잠그는 이유는 "활성 Context 1개 이상" 불변식과 `record_count` 산술이 동시 요청과 경합하기 때문이다. 탈퇴는 그 회원의 데이터를 전부 지우므로 **개수를 세어 분기할 일이 없고**, Collection은 소유자 자기 Record만 담아(`CollectionService.requireAllOwnedActiveRecords`) 타 회원과 경합하지도 않는다.

> `#34` 코멘트의 *"`invalidate`를 Collection 잠금보다 앞에 두라"*는 조언은 잠금이 있다는 전제였다. 전제가 사라졌으므로 **순서만 지켰다**(무효화가 Collection 처리보다 앞). 리뷰에서 뒤집힐 수 있는 판단이라 PR에 명시했다.

**② `record_count`를 갱신하지 않는다.** Collection 자체가 함께 죽는다. `CollectionService.deleteCollection`이 같은 이유로 갱신하지 않는다.

**③ Refresh 폐기를 트랜잭션 마지막에 둔다.** 근거는 BD-41에 있다 — 실패가 "아무 일도 없었음"으로 수렴하는 순서다.

**④ 연쇄를 도메인별 서비스로 흩뜨리지 않는다.** `§6.9`가 정한 것이 순서이므로 한 곳에서 읽혀야 하고, 각 단계가 같은 트랜잭션에 있다는 보장도 흩뜨리면 호출부마다 따라가 봐야 알게 된다. 타 도메인 리포지토리 주입은 `RecordDeletionService`에 선례가 있다.

## 드러난 것

**① CSRF가 인가보다 먼저 돈다.** RED에서 `미인증 → 401`을 기대한 테스트가 **403**으로 실패했다. `CsrfFilter`가 앞에 있어 토큰 없는 `DELETE`는 인증 여부와 무관하게 403이다. `authentication.md`가 403을 CSRF 전용으로 정해 뒀으므로 테스트를 둘로 갈랐다 — 미인증(CSRF 토큰은 주고) → 401, CSRF 누락(인증은 주고) → 403이며 후자는 아무것도 지워지지 않는 것까지 단언한다.

**② `loginAs`로는 인증 필터를 검증할 수 없다.** Access 창 테스트가 처음 실패했는데 원인이 구현이 아니라 테스트였다. `AuthTestSupport.loginAs`는 인증 객체를 `SecurityContext`에 직접 주입하고, `JwtAuthenticationFilter`는 컨텍스트가 비어 있을 때만 도는 탓에 **통째로 건너뛰어진다.**

그 한 건만 `JwtTokenProvider.issueAccessToken`으로 실제 토큰을 만들어 쿠키에 실었다. **탈퇴 전 200 → 탈퇴 후 401**로 두 번 호출하는 형태이고, 앞쪽 단언이 없으면 안 된다 — 쿠키 이름을 틀리게 바꿔 확인했더니 **앞쪽만 실패하고 뒤쪽은 통과했다.** 즉 앞쪽이 없으면 토큰이 한 번도 읽히지 않아도 초록불이 되는 vacuous pass가 된다.

**③ 기존 테스트는 하나도 깨지지 않았다.** 인증 필터에 DB 조회를 추가했는데 407개 전부 통과한다. 도메인 테스트는 `loginAs`로 필터를 우회하고, 실제 쿠키를 쓰는 `AuthTokenContractTests`는 콜백으로 진짜 회원 행을 만들기 때문이다. 존재하지 않는 memberId로 Access를 위조하는 테스트는 `JwtTokenProviderTest`(단위, 필터 미경유)뿐이었다.

## 감수하는 것

- **인증 요청마다 PK 조회 1회.** 근거와 재검토 트리거는 BD-41에 있다.
- **탈퇴는 되돌릴 수 없다.** 삭제는 모두 소프트 삭제지만 `social_account`의 개인정보는 마스킹으로 파기되므로 복구 경로가 없다. 운영자 복구도 새 INSERT로만 가능하다(06 §7).
- **물리 삭제 시점은 이 구현의 범위가 아니다.** 개인정보 정책을 따른다(07 §5). 소프트 삭제 데이터 보존 기간은 공용 계약의 미확정 항목이다(06 §8).

## 검증

`./gradlew clean check --no-daemon` — **407개 통과, 실패 0, 오류 0.**

신규 테스트 13건은 전부 PostgreSQL Testcontainers 기반이고, 삭제 표시된 행은 `@SQLRestriction` 때문에 리포지토리로 보이지 않아 **확인을 `JdbcTemplate` native 쿼리로 한다**(`MemberSoftDeleteTests`와 같은 방식).

**뮤테이션 3종 — 각 방어선이 독립이다.** 되돌릴 때 실패하는 테스트가 1건씩이고 나머지 12건은 통과한다.

| 되돌린 것 | 실패 |
|---|---|
| 필터의 `.filter(memberRepository::isActive)` | Access 창 1건 |
| `aiDerivedDataRepository.invalidate(...)` | AI 파생 1건 |
| `withdraw()`의 이메일 치환 | 마스킹 1건 |

세 번째는 **DB `NOT NULL`이 잡지 못한다** — 원본 이메일이 그대로 남아 제약 위반이 아니다. `BI-27`에서는 두 층(정규화·DB 제약)이 서로를 보완했지만, 여기서는 테스트가 유일한 방어선이다.

## 남은 것

- **탈퇴 후 재로그인 검증이 리포지토리 수준이다.** 활성 행이 사라져 부분 유니크가 재가입을 허용하는 것까지는 확인했지만, 실제 OAuth 콜백을 한 번 더 도는 형태는 아니다. 콜백의 기존 회원 판정은 `SocialAccountRepository.findByProviderAndProviderUserId`가 담당하고 그 계약은 `GoogleLoginCallbackTests`가 이미 고정한다.
- **연쇄 대상에 `place`가 없다.** 공용 데이터라 유지한다(06 §6.9).
- **`ai.context_keyword`는 별도 처리가 없다.** 조회가 `context_ai_state`를 조인해 `keyword_status`로 거르므로 `CANCELLED` 전이만으로 제외된다(08 §3.6).
