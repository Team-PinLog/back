# 명세 (Spec)

백엔드 도메인의 설계·구현 명세 — "무엇을 만들 것인가"를 담습니다.

이 폴더는 **살아있는 문서**입니다. 항상 현재 유효한 명세만 남깁니다.

- 설계가 바뀌면 → 문서를 제자리에서 수정
- 명세가 폐기되면 → 문서 삭제 (폐기 사유가 결정이라면 [`decisions/`](../decisions/)에 BD로 기록)
- 과거 이력 추적은 이 폴더가 아니라 [`decisions/`](../decisions/)·[`implements/`](../implements/)가 담당

## 목록을 따로 두지 않는다

폴더 목록이 곧 색인입니다. 목록 표를 따로 두면 동시에 열린 브랜치가 같은 파일을 고치게 되어 충돌합니다([back#133](https://github.com/Team-PinLog/back/issues/133), [BD-45](../decisions/BD-45-worklog-per-entry-files.md)). 새 명세는 `<기능명>.md`로 폴더에 추가하며 별도 등록 절차는 없습니다.

```bash
find docs/backend/spec -maxdepth 1 -name '*.md' -not -name 'README.md' | sort
```
