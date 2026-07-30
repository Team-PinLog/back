# BI-26. 운영 Secret 전달 workflow

- **상태**: ✅ 완료
- **날짜**: 2026-07-29
- **관련**: S15P11A705-154, [infra#78](https://github.com/Team-PinLog/infra/pull/78)

## 산출

- 수동 실행 전용 `.github/workflows/seal-runtime-secrets.yml`을 추가했다.
- `pinlog-secrets-prod` Environment가 소유한 런타임 키 8개와 Infra PR bridge token 1개만 SHA 고정 composite action에 전달한다.
- checkout 대상과 provenance revision은 실행 시점의 `github.sha`로 고정하고 checkout credential은 남기지 않는다.

## 경계

일반 `backend-ci`는 런타임 Secret을 읽지 않는다. 이 workflow만 `contents: read`, `id-token: write` 권한과 `pinlog-secrets-prod` Environment 경계를 가지며, 실제 Secret 값이나 GitHub 설정은 이 변경에서 조회·수정하지 않았다.

## 검증

`RuntimeSecretWorkflowContractTests`가 수동 trigger, Environment, 최소 권한, immutable action/source revision, 정확히 9개인 Secret 참조 집합을 정적으로 고정한다.

## 정정 (2026-07-30)

**Secret 개수를 "런타임 5개 + bridge 1개 = 6개"로 적었으나 실제는 "런타임 8개 + bridge 1개 = 9개"였다.** 산출·검증 두 문단의 숫자를 고쳤다. 서술만 틀렸고 워크플로와 계약 테스트는 처음부터 9개를 고정하고 있었다.

```
JWT_PRIVATE_KEY
GOOGLE_CLIENT_ID · GOOGLE_CLIENT_SECRET
KAKAO_CLIENT_ID  · KAKAO_CLIENT_SECRET
NAVER_CLIENT_ID  · NAVER_CLIENT_SECRET
PINLOG_AI_INTERNAL_SECRET          ← 런타임 8개
PINLOG_INFRA_SECRET_PR_TOKEN       ← bridge token
```

근거는 `.github/workflows/seal-runtime-secrets.yml` 22~31행과 `RuntimeSecretWorkflowContractTests`의 `containsExactlyElementsOf` 단언이다. 후자가 9개를 그대로 열거하므로, 숫자가 6이었다면 이 기록과 통과하는 테스트가 서로 어긋난 상태였다.

봉인 정책(`infra/policy/sealedsecrets/back-prod.yaml`)이 요구하는 키는 **7개**로 또 다른 값이다 — 위 목록에서 `PINLOG_AI_INTERNAL_SECRET`과 bridge token을 뺀 집합이다([back#105](https://github.com/Team-PinLog/back/issues/105)). 세 숫자(7·8·9)가 각각 다른 것을 세므로 함께 적어 둔다.
