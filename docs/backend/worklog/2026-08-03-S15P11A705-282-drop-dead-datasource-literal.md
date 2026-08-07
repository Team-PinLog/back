# application-prod.yml의 죽은 datasource·Redis 리터럴을 지운다

- **날짜**: 2026-08-03
- **추적**: S15P11A705-282
- **관련**: [BD-50](../decisions/BD-50-datasource-redis-config-follows-infra-env-vars.md) · [back#164](https://github.com/Team-PinLog/back/pull/164)

`configuration.md`를 실제 infra 계약에 맞추다가, `application-prod.yml`과 `ConfigurationContractTests`가 여전히 "리터럴 FQDN + `DB_PASSWORD`" 옛 계약을 파일 자체로 고정하고 있는 걸 발견했다. Spring Boot는 OS 환경변수를 profile별 yaml보다 우선하므로, infra가 실제로 주입하는 `SPRING_DATASOURCE_*`·`SPRING_DATA_REDIS_*` 다섯 개가 이 yaml을 항상 이겼다 — "접속 정보는 코드로 통제한다"는 설계 의도가 애초에 지켜진 적이 없었다.

`application-prod.yml`에서 datasource·redis 블록을 지우고, `ConfigurationContractTests`도 "이 키들을 재선언하지 않는다"만 확인하도록 바꿨다. 실제 런타임 동작(어차피 env var가 이기고 있었으므로)은 바뀌지 않는다. 판단 근거는 [BD-50](../decisions/BD-50-datasource-redis-config-follows-infra-env-vars.md)에 남겼다.
