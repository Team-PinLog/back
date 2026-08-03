# BD-46. datasource·Redis 접속 정보는 코드 리터럴이 아니라 infra 환경변수를 따른다

- **상태**: Accepted
- **날짜**: 2026-08-03
- **관련**: [back#164](https://github.com/Team-PinLog/back/pull/164), `infra/apps/prod/back/values.yaml`, [`docs` 레포 `static/12_배포_변수_및_Secret_표준.md`](https://github.com/Team-PinLog/docs/blob/main/static/12_배포_변수_및_Secret_표준.md)

## 맥락

`application-prod.yml`은 datasource url·username(FQDN `postgres.pinlog-prod.svc.cluster.local`, `pinlog`)과 Redis host를 리터럴로, 비밀번호만 `${DB_PASSWORD}`로 적어 뒀다. `ConfigurationContractTests`가 이 값들을 파일 자체로 고정하고 있었고, 의도는 "접속 정보 변경이 반드시 git PR·리뷰를 거치게 한다"는 것이었다(주석에 명시).

이 문서·테스트를 12번 문서(`docs/static/12_배포_변수_및_Secret_표준.md`)와 대조하다가, 실제 `infra/apps/prod/back/values.yaml`이 `SPRING_DATASOURCE_URL`·`SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD`·`SPRING_DATA_REDIS_HOST`·`SPRING_DATA_REDIS_PORT`를 Spring Boot 표준 relaxed-binding 이름 그대로 k8s 환경변수로 주입하고 있음을 확인했다. Spring Boot의 프로퍼티 소스 우선순위는 OS 환경변수가 profile별 `application-{profile}.yml`보다 높다. 즉 이 다섯 개 env var가 이미 존재하는 한, yaml에 뭘 적어놔도 무시된다 — `DB_PASSWORD`는 어디서도 주입된 적이 없어 죽은 placeholder였고, 실제 접속 주소도 파일의 FQDN이 아니라 env var의 짧은 in-cluster 이름(`postgres:5432`)이었다.

"config는 코드다"라는 설계 의도는 이 발견 이전부터 이미 지켜지지 않고 있었다 — 코드가 통제권을 가진 적이 없었다.

## 선택지

| 안 | 장점 | 단점 |
|---|---|---|
| (a) infra에 요청해 5개 env var를 걷어내고 `DB_PASSWORD` 하나만 주입하도록 되돌린다 | 원래 설계("코드가 정본") 회복 | infra PR·롤아웃 조율 필요, 이미 정상 동작 중인 값을 굳이 바꾸는 리스크, 팀 공용 정본(12번 문서)과도 다시 어긋남 |
| (b) 코드의 리터럴·테스트를 지우고 infra의 실제 계약(표준 이름 env var)을 그대로 받아들인다 | 즉시 정합, infra 변경 불필요, 12번 문서와 일치 | back 레포가 DB/Redis 접속 정보 변경에 대한 리뷰 가시성을 잃는다 |

## 결정

(b)를 선택했다. **제약으로 주어짐** — 이미 운영 중인 infra 계약과 팀 공용 정본 문서가 표준 이름 env var 방식으로 굳어져 있고, (a)는 이미 잘 동작하는 배포를 건드리면서까지 지금까지 한 번도 실제로 지켜진 적 없던 보장을 되살리는 것이라 비용 대비 실익이 낮다고 판단했다.

`application-prod.yml`에서 `spring.datasource.*`·`spring.data.redis.*`를 전부 삭제하고, `ConfigurationContractTests`도 "이 키들을 재선언하지 않는다"를 검증하는 쪽으로 바꿨다.

## 결과

- 이 결정으로 감수하는 것: DB/Redis 접속 주소·자격증명 이름이 바뀌어도 back 레포는 그 사실을 PR로 보지 못한다. 전적으로 infra 레포의 `values.yaml` 변경 이력에 의존한다.
- 재검토 트리거: back이 다시 "접속 정보 변경은 반드시 백엔드 PR을 거친다"는 보장을 원하게 되면, infra와 조율해 (a)로 되돌리고 이번처럼 코드·문서·정본 세 곳이 다시 어긋나지 않도록 12번 문서도 함께 갱신해야 한다.
