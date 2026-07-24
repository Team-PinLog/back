# 테스트 개발 규약

시작 절차와 PR 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 테스트 종류와 필수 검증 기준입니다.

## 테스트 종류

- 모든 순수 단위 테스트에는 Spring Context를 올리지 않습니다. 외부 의존성은 테스트 대역으로 분리해 빠르고 독립적으로 검증합니다.
- DB가 필요한 모든 테스트는 PostgreSQL Testcontainers를 사용합니다. Repository, Flyway와 DB 통합 테스트도 포함하며, H2 또는 인메모리 DB로 대체하지 않습니다.
- Docker가 실행되지 않은 환경에서는 DB 테스트를 skip하지 않습니다. Docker 연결 문제를 드러내며 실패해야 합니다.

## PR별 필수 테스트

| PR 변경 유형 | 필수 테스트와 검증 |
| --- | --- |
| 일반 코드 | 변경한 동작의 단위 또는 통합 테스트와 `./gradlew clean check --no-daemon` |
| Repository 또는 DB 접근 | PostgreSQL Testcontainers 통합 테스트와 전체 check |
| Flyway migration | 빈 PostgreSQL DB에서 전체 migration 검증과 전체 check |
| API 계약 | 정상 요청, validation HTTP 400, 변경된 오류·pagination 계약 테스트와 문서 갱신 |
| 인증·인가 | 성공, 미인증 401, 권한 부족 403 또는 확정된 리소스 은닉 404 테스트 |

`./gradlew clean check --no-daemon`은 완료 보고 전의 공통 필수 검증입니다. 이 명령은 Testcontainers를 사용할 수 있도록 Docker가 실행 중인 상태에서 실행합니다.

## 테스트 작성 순서

기존 테스트를 먼저 확인하고, 요구사항을 증명하는 실패 테스트를 먼저 작성하거나 갱신합니다. 구현 뒤에는 해당 테스트와 전체 check를 다시 실행하고, PR 본문에 실행한 명령과 결과를 기록합니다.
