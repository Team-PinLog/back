# 겹쳐 있던 BD-46 두 개 중 나중에 머지된 쪽을 BD-50으로 옮긴다

- **날짜**: 2026-08-07
- **관련**: [BD-45](../decisions/BD-45-worklog-per-entry-files.md) · [BD-50](../decisions/BD-50-datasource-redis-config-follows-infra-env-vars.md) · [BD-46](../decisions/BD-46-list-sort-default-asc-with-params.md)

`docs/backend/decisions/`에 `BD-46`이 두 개 있었다. 서로 다른 결정인데 소스 주석 여러 곳이
"BD-46"이라고만 적어 참조해서, 읽는 사람이 어느 쪽을 가리키는지 알 수 없었다.

## 어느 쪽이 번호를 내놓는가

`decisions/README.md`가 정해 준다 — **번호는 `dev` 머지 순서대로 확정되고, 미머지 브랜치가 파일명으로
선점한 번호는 예약이 아니다.** 둘 다 2026-08-03에 만들어졌지만 머지 순서가 다르다.

| 결정 | PR | dev 머지 순서 | 결과 |
| --- | --- | --- | --- |
| 목록 기본 정렬을 오래된순으로 | [#168](https://github.com/Team-PinLog/back/pull/168) | 먼저 | `BD-46` 유지 |
| datasource·Redis는 infra 환경변수를 따른다 | [#170](https://github.com/Team-PinLog/back/pull/170) | 나중 | `BD-50`으로 이동 |

다음 빈 번호가 50이다(기존 최댓값 49). 열려 있는 PR 두 개(#179·#200)가 BD 문서를 만들지 않아
선점 충돌도 없었다.

## 참조를 함께 고친 범위

`BD-50`으로 옮긴 결정을 가리키는 곳만 고쳤다 — 정렬 결정을 가리키는 "BD-46" 20여 곳은 그대로 맞다.
그래서 일괄 치환이 아니라 대상을 짚어 고쳤다.

- `docs/development/configuration.md` 2곳(살아 있는 문서)
- `src/main/resources/application-prod.yml` 1곳
- `src/test/java/.../ConfigurationContractTests.java` 3곳
- `docs/backend/worklog/2026-08-03-drop-dead-datasource-literal.md` 2곳

**마지막 항목은 판단이 필요했다.** 작업 로그는 보존 구역이고 `worklog/README.md`는 갱신하지 말고 새
항목을 더하라고 한다. 그런데 파일을 옮기면 그 항목의 링크가 죽는다. 기록의 서술은 한 글자도 건드리지
않고 **옮겨 간 파일을 가리키도록 링크만** 고쳤다 — 기록을 고친 것이 아니라 가리키는 곳이 움직인 것을
따라간 것으로 본다. 이 판단에 이견이 있으면 링크를 되돌리고 이 항목만 남기는 쪽으로 바꿀 수 있다.

## BD-45가 이것을 예측해 뒀다

BD-45가 작업 로그를 항목별 파일로 쪼갤 때 "전역 번호는 범위 밖"이라며 든 예가 하필
`BD-46-foo.md`·`BD-46-bar.md`였고, 근거가 "지금까지 중복 0건이고, 겹쳐도 고치는 값이 파일 rename
하나로 싸다"였다. 나흘 뒤에 실제로 일어났다. 값 판단 자체는 성립했다 — rename 하나와 참조 8곳이
전부였다. BD-45에 그 사실을 후속으로 적었고 결정은 유지한다.

**재발 방지는 하지 않았다.** 채번을 사람의 조회에 맡기는 한 다시 일어난다. 중복 번호를 잡는 CI 검사가
값이 맞아 보이지만, 이 작업의 범위가 아니라서 손대지 않았다.
