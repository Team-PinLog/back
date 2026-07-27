# Claude Code 훅 (팀 공용)

에이전트가 문서로만 있는 규칙을 어기지 못하도록 기계적으로 막는 훅입니다. `.claude/settings.json`에 등록되어 있으며 back 저장소에서 작업하는 모든 사람에게 동일하게 적용됩니다.

| 훅 | 이벤트 | 하는 일 |
|---|---|---|
| [`protect-migrations.sh`](protect-migrations.sh) | PreToolUse (Edit·Write) | 커밋된 Flyway 마이그레이션 수정 차단, 중복 버전 번호 생성 차단 |
| [`verify-check.sh`](verify-check.sh) | Stop | 소스 변경이 `./gradlew clean check --no-daemon` 검증 없이 완료되는 것을 차단 |
| [`lib/hook-input.sh`](lib/hook-input.sh) | — | 두 훅이 공유하는 입력 JSON 파싱·경로 정규화 |

## 환경 전제

- **⚠ back 저장소 루트에서 Claude Code를 열어야 합니다.** 훅은 **실행한 디렉터리**의 `.claude/settings.json`과 유저 설정에서만 로드되고, 하위 디렉터리의 설정은 읽지 않습니다. 여러 저장소를 한 폴더(`pinlog/back`, `pinlog/ai` …)에 클론해두고 그 **상위 폴더에서 claude를 열면 이 훅들은 등록조차 되지 않습니다.** 스킬은 반대로 하위 디렉터리에서도 발견되지만 이름이 `/back:pr`처럼 한정됩니다.
- **bash 필요.** Windows에서 Claude Code는 Git Bash가 설치돼 있으면 bash로, 없으면 PowerShell로 훅을 실행합니다. PowerShell로 떨어지면 이 훅들은 동작하지 않으므로 **Git for Windows 설치가 사실상 필수**입니다. macOS·Linux는 셸 폼 훅을 `sh -c`로 실행하므로, `settings.json`에서 `bash <스크립트>` 형태로 호출합니다.
- **jq는 없어도 됩니다.** Claude Code는 jq를 번들하지 않고 Windows Git Bash 기본 설치에도 없어서, `lib/hook-input.sh`가 jq → sed 폴백으로 파싱합니다. jq가 있으면 그쪽을 씁니다.
- 경로 표기는 `lib/hook-input.sh`의 `hook_normalize_path`가 흡수합니다. Windows는 `file_path`를 `C:\...` 백슬래시로 넘기므로 **경로 매칭 전에 반드시 정규화**해야 합니다. 정규화를 빼면 훅이 조용히 통째로 무력화됩니다.

## 스크립트를 고칠 때

**GNU 전용 문법을 쓰지 마세요.** macOS는 BSD sed라 `\|`(교대)·`\U`(대문자 변환) 같은 GNU 확장이 없고, 매칭이 실패하면 빈 값이 되어 **훅이 차단하지 않고 통과합니다**. 실패가 눈에 띄지 않는 게 이 훅들의 가장 위험한 고장 방식입니다. POSIX BRE 범위로만 작성하고, 아래로 확인하세요.

```bash
PATH=$(mktemp -d):$PATH  # 그 안에 `exec sed --posix "$@"` 래퍼를 두고
bash .claude/hooks/tests/run-tests.sh
```

## 테스트

훅을 고치면 반드시 돌립니다. 저장소를 변경하지 않습니다.

```bash
bash .claude/hooks/tests/run-tests.sh
```

경로 표기(POSIX·Windows) × jq 유무의 4개 조합을 모두 검사합니다. **축을 줄이지 마세요** — 실제로 이 두 축에서 훅이 무력화된 적이 있습니다.

## 알려진 한계

- `verify-check.sh`는 커밋되지 않은 변경만 봅니다. 검증 없이 커밋까지 마치고 종료하면 놓치며, 그 경우는 CI `backend-ci / check`가 잡습니다.
- 소유 구간(백엔드 `V2`~`V99`, AI `V100`~`V199`)은 훅으로 강제하지 않습니다 — 문서와 PR 리뷰 담당입니다.
- "커밋됨 = 적용됨" 휴리스틱이라, 커밋했지만 아직 배포 전인 마이그레이션 수정도 차단됩니다. 새 버전 추가로 우회할 수 있어 보수적인 쪽을 택했습니다.
