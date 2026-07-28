# BT-02. 백엔드 migration이 AI 구간보다 낮은 번호라 Flyway validate가 실패한다

- **상태**: ✅ 해결 (2026-07-28, S15P11A705-86) — 아래 "해결" 절 참고. 구간 소유를 유지하고 `out-of-order`를 허용하는 (a)를 채택했다([BD-26](../decisions/BD-26-flyway-out-of-order.md))
- **날짜**: 2026-07-27
- **레이어**: Flyway / 배포
- **관련**: S15P11A705-51 검증 중 발견, S15P11A705-66(다음 백엔드 migration)에 직접 영향. 해소는 S15P11A705-86

## 증상

기존 로컬 `postgres-data` volume에 대해 `dev` 브랜치 jar를 기동하면 애플리케이션이 뜨지 않는다.

```
Caused by: org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed validation
Detected resolved migration not applied to database: 2.
```

```
 version |  description   | installed_on
---------+----------------+----------------------------
 1       | create schemas | 2026-07-24 15:17:53
 100     | ai tables      | 2026-07-24 15:17:53
 101     | ai indexes     | 2026-07-24 15:17:53
 102     | feed event     | 2026-07-24 15:17:53
```

DB에는 `V1`·`V100`~`V102`가 적용돼 있고, 레포에는 그 뒤에 추가된 `V2__member.sql`이 있다.

## 원인

**버전 구간으로 소유권을 나눈 구조가 out-of-order를 구조적으로 보장한다.**

[데이터베이스 규약](../../development/database-conventions.md)의 소유 경계는 `V1` 공통 / `V2`~`V99` 백엔드 / `V100`~`V199` AI다. AI 구간이 백엔드 구간보다 **위**에 있으므로, AI migration이 이미 적용된 DB에 백엔드가 새 migration을 추가하면 그 번호는 항상 적용된 최대 버전(`102`)보다 낮다. Flyway는 기본적으로 이를 out-of-order로 보고 `validate`에서 거부한다.

즉 이번 `V2`만의 문제가 아니라 **앞으로 추가될 모든 백엔드 migration(`V3`~`V99`)이 같은 실패를 낸다.**

### CI가 못 잡는 이유

`FlywayMigrationTests`는 **빈 DB**에서 시작한다. 빈 DB에서는 `V1 → V2 → V100 → V102` 순서대로 적용되므로 out-of-order가 발생하지 않고 항상 초록불이다. 이 실패는 **이미 AI migration이 적용된 DB**에서만 나타난다.

### 운영에 미치는 영향

- 최초 배포(S15P11A705-48)는 빈 DB라 통과한다.
- 그 이후 백엔드가 migration을 하나라도 추가하면(예: S15P11A705-66의 core 테이블) **다음 배포에서 애플리케이션이 기동 실패한다.** RollingUpdate 중이라면 새 Pod가 CrashLoop에 빠지고 롤아웃이 멈춘다.

## 확인 방법 (진단 당시)

로컬에서 상태를 맞추려면 out-of-order를 1회 허용해 적용했다. 이 시점에는 설정으로 커밋하지 않았다.

```bash
SPRING_FLYWAY_OUT_OF_ORDER=true java -jar build/libs/*.jar
```

```
Migrating schema "public" to version "2 - member" [out of order]
Successfully applied 1 migration
```

이 1회 확인이 (a)의 근거가 되었고, 아래 "해결"에서 상시 설정으로 승격했다.

## 해결 (2026-07-28, S15P11A705-86)

**(a)를 채택했다** — 구간 소유 구조를 유지하고 `application.yml`에 `spring.flyway.out-of-order: true`를 적용했다. 결정 배경과 감수 항목은 [BD-26](../decisions/BD-26-flyway-out-of-order.md)에 있다.

**"CI가 못 잡는다"는 공백도 함께 메웠다.** `FlywayOutOfOrderTests`가 AI 구간만 적용된 DB를 재현한 뒤 백엔드 마이그레이션이 적용되는지 검증한다. 재현 방식은 전체를 적용한 뒤 백엔드 몫만 이력에서 되돌리는 것인데, 특정 버전만 골라 적용하는 `cherryPick`이 Flyway 상용 기능이라 community 판에서 쓸 수 없기 때문이다. 이 테스트를 설정 적용 **전에** 돌려 위 증상의 예외가 그대로 재현되는 것을 확인했다.

남은 것: 적용 순서 변화는 두 파트에 함께 영향을 주므로 **AI 파트 통지**가 필요하다(P## 절차가 필요한지 포함).

## 선택지 (검토 당시)

| 안 | 장점 | 단점 |
|---|---|---|
| (a) `spring.flyway.out-of-order=true` 상시 적용 | 구간 소유 구조를 그대로 유지. 설정 한 줄 | 적용 순서가 환경마다 달라진다(빈 DB는 `V2→V100`, 기존 DB는 `V100→V2`). 두 구간이 서로 의존하면 결과가 갈린다 |
| (b) 백엔드가 AI 구간 위 번호를 쓰도록 재배치 | out-of-order가 원천적으로 사라짐 | 규약·문서 전면 수정. AI 파트 합의 필요. 앞으로 두 파트가 번호를 계속 조율해야 함 |
| (c) 스키마별로 Flyway 인스턴스 분리 (`core`/`ai` 각각 history 테이블) | 소유가 물리적으로 분리됨. 가장 깨끗함 | 설정 복잡도 상승. 기존 `public.flyway_schema_history` 이관 필요 |

(a)의 단점은 현재 구조에서는 크지 않다. `core`와 `ai`는 스키마가 분리돼 있고 서로의 테이블을 정의하지 않으므로 적용 순서가 결과를 바꾸지 않는다. 다만 `core.feed_event`가 AI 소유 `V102`에 있어 완전한 분리는 아니다 — 이 예외가 (c)를 검토할 이유이기도 하다.

## 재발 방지

- `FlywayOutOfOrderTests`가 이 경로를 자동으로 감시한다. 빈 DB만 검증하는 `FlywayMigrationTests`와 **둘 다** 유지한다 — 하나는 처음 배포, 하나는 그 이후를 지킨다.
- `spring.flyway.out-of-order`를 끄려면 먼저 [BD-26](../decisions/BD-26-flyway-out-of-order.md)의 재검토 트리거를 확인한다. 끄는 순간 이 증상이 그대로 돌아온다.
- 백엔드가 `core.feed_event`나 `ai` 스키마 객체에 의존하는 제약을 추가하려 하면, 적용 순서가 결과를 바꾸므로 BD-26을 먼저 재검토한다.
