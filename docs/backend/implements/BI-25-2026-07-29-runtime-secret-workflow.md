# BI-25. 운영 Secret 전달 workflow

- **상태**: ✅ 완료
- **날짜**: 2026-07-29
- **관련**: S15P11A705-154, [infra#78](https://github.com/Team-PinLog/infra/pull/78)

## 산출

- 수동 실행 전용 `.github/workflows/seal-runtime-secrets.yml`을 추가했다.
- `pinlog-secrets-prod` Environment가 소유한 런타임 키 5개와 Infra PR bridge token 1개만 SHA 고정 composite action에 전달한다.
- checkout 대상과 provenance revision은 실행 시점의 `github.sha`로 고정하고 checkout credential은 남기지 않는다.

## 경계

일반 `backend-ci`는 런타임 Secret을 읽지 않는다. 이 workflow만 `contents: read`, `id-token: write` 권한과 `pinlog-secrets-prod` Environment 경계를 가지며, 실제 Secret 값이나 GitHub 설정은 이 변경에서 조회·수정하지 않았다.

## 검증

`RuntimeSecretWorkflowContractTests`가 수동 trigger, Environment, 최소 권한, immutable action/source revision, 정확히 6개인 Secret 참조 집합을 정적으로 고정한다.
