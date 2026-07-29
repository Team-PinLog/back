# 트러블슈팅 (Troubleshooting)

백엔드 구현·문서 작업 중 겪은 문제와 그 해결을 `BT-##` 번호로, 재현 가능한 형태(증상 → 원인 → 해결 → 재발 방지)로 남깁니다.

## 보존 원칙

이 폴더는 문제 해결 과정을 기록합니다. **해결된 항목도 삭제하지 않고 상태 표시만 갱신합니다.** 회고와 복기에서 "무엇을 어떻게 해결했는가"를 추적하기 위함입니다.

- 해결됨 → 문서 유지 + `상태: 해결됨` + 해결 경로·링크 추가
- 무효화 → 문서 유지 + `상태: 무효(사유)` 표기
- 삭제 → 하지 않음. 잘못 작성된 문서도 정정으로 처리

## 목록

| BT | 증상 | 상태 | 문서/해결 |
|---|---|---|---|
| [BT-01](BT-01-shared-testcontainers-lifecycle.md) | 공유 Testcontainers Postgres가 클래스마다 재시작되어 뒤 클래스가 죽은 포트를 물음 | 해결됨 | 싱글톤 컨테이너 패턴 전환 (S15P11A705-41, `6c61272`) |
| [BT-02](BT-02-flyway-out-of-order-version-ranges.md) | AI 구간(`V100~`)이 적용된 DB에 백엔드 migration(`V2~V99`)을 추가하면 Flyway validate 실패 | ⚠️ 미해결 | 파트 간 합의 필요. 빈 DB만 보는 CI는 못 잡음 (S15P11A705-51 검증 중 발견) |
| [BT-03](BT-03-health-endpoint-blocks-on-redis-outage.md) | Redis 장애 시 집계 `/actuator/health`가 60초간 블로킹, liveness·readiness는 의존성 장애를 반영하지 않음 | ⚠️ 미해결 | Infra와 probe 경로·구성 합의 필요 (S15P11A705-51 검증 중 발견) |
| [BT-04](BT-04-logged-in-cookie-path-unreadable.md) | `logged_in` 쿠키가 `Path=/api/core`로 발급돼 프론트 JS가 읽을 수 없음 — `HttpOnly`만 끄면 읽힌다고 본 것이 원인 | 해결됨 | `Path=/`로 수정 + `Path` 회귀 단언 추가 (S15P11A705-63, 실제 Google 수동 검증 중 발견) |
| [BT-05](BT-05-dotenv-empty-value-overrides-default.md) | `.env`의 빈 값이 `application.yml` 기본값을 덮어 `Client id ... must not be empty`로 기동 실패. Spring Boot 4가 `.env`를 자동 로드한다 | 해결됨 | `.env.example`의 자격증명 6줄을 주석으로 (S15P11A705-64, Kakao·Naver 등록 중 발견) |
