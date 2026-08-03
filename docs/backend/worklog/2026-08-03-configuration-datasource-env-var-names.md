# configuration.md의 datasource·Redis 계약을 실제 배포와 맞춘다

- **날짜**: 2026-08-03

`docs/development/configuration.md`의 "비밀값과 자격증명 주입" 절이 "환경변수로 주입되는 건 `DB_PASSWORD` 하나뿐, url·username·host·port는 yaml 리터럴"이라고 적고 있었다. 실제 `infra/apps/prod/back/values.yaml`을 확인하니 `SPRING_DATASOURCE_URL`·`SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD`·`SPRING_DATA_REDIS_HOST`·`SPRING_DATA_REDIS_PORT` 다섯 개가 전부 실제 k8s 환경변수로 주입되고 있었고, 이는 팀 공용 `docs` 레포의 `docs/static/12_배포_변수_및_Secret_표준.md`(정본 표)와 일치했다. `DB_PASSWORD`라는 이름은 실제로 쓰인 적이 없었다.

문서를 실제 계약(Spring Boot relaxed-binding 표준 이름, `application.yml`에는 datasource·redis 설정을 아예 적지 않음)에 맞춰 고쳤다. `infra/docs/backend-conventions.md` §5도 같은 이유로 낡아 있어 인프라 담당자(김세민)에게 별도로 전달했다 — 이 레포에서는 고칠 수 없는 범위다.
