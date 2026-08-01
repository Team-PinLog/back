# 트러블슈팅 (Troubleshooting)

백엔드 구현·문서 작업 중 겪은 문제와 그 해결을 `BT-##` 번호로, 재현 가능한 형태(증상 → 원인 → 해결 → 재발 방지)로 남깁니다.

## 보존 원칙

이 폴더는 문제 해결 과정을 기록합니다. **해결된 항목도 삭제하지 않고 상태 표시만 갱신합니다.** 회고와 복기에서 "무엇을 어떻게 해결했는가"를 추적하기 위함입니다.

- 해결됨 → 문서 유지 + `상태: 해결됨` + 해결 경로·링크 추가
- 무효화 → 문서 유지 + `상태: 무효(사유)` 표기
- 삭제 → 하지 않음. 잘못 작성된 문서도 정정으로 처리

## 목록을 따로 두지 않는다

목록 표를 두면 모든 브랜치가 같은 파일을 고치게 되어 PR끼리 충돌하고, 손으로 옮겨 적은 사본이라 실제로 어긋납니다. 이 표는 `BT-02`가 해결된 뒤에도 미해결로 적어두고 있었습니다([back#133](https://github.com/Team-PinLog/back/issues/133), [BD-45](../decisions/BD-45-worklog-per-entry-files.md)). 각 문서의 H1이 번호와 요약을, 헤더가 상태를 원본으로 갖고 있으므로 폴더에서 직접 읽습니다.

```bash
grep -h '^# B' docs/backend/troubleshooting/BT-*.md | sort      # 전체 목록
grep -H '^- \*\*상태\*\*' docs/backend/troubleshooting/BT-*.md   # 상태별로 훑기
grep -l '^- \*\*상태\*\*.*미해결' docs/backend/troubleshooting/BT-*.md   # 미해결만
```
