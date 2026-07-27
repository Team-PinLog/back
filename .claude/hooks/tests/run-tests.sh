#!/bin/bash
# 훅 시나리오 테스트 — 저장소를 변경하지 않는다(읽기 전용 검사 + 임시 픽스처).
#
# 실행: bash .claude/hooks/tests/run-tests.sh
#
# 경로 표기(POSIX `/c/...` · Windows `C:\...`)와 jq 유무(설치됨 · 없음) 조합을 모두 돌린다.
# 훅이 무력화된 사고가 이 두 축에서 나왔으므로 축을 줄이지 말 것.
set -u

TESTS_DIR=$(cd "$(dirname "$0")" && pwd)
HOOKS_DIR=$(dirname "$TESTS_DIR")
REPO_ROOT=$(cd "$HOOKS_DIR/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
SKIPPED=""

# 테스트 입력 JSON 생성용 인터프리터.
# macOS·대부분의 Linux에는 `python`이 없고 `python3`만 있다. 반대로 Windows에는
# 실행하면 스토어 안내만 출력하는 가짜 `python3`(앱 실행 별칭)이 PATH에 있다.
# 그래서 "존재하는가"가 아니라 "실제로 동작하는가"로 고른다.
PY=""
for CAND in python3 python py; do
  if command -v "$CAND" >/dev/null 2>&1 && "$CAND" -c 'import json' >/dev/null 2>&1; then
    PY=$(command -v "$CAND")
    break
  fi
done
if [ -z "$PY" ]; then
  echo "동작하는 python3/python이 필요합니다(테스트 입력 JSON 생성에 사용)." >&2
  exit 1
fi
export PY

# jq가 없는 환경을 재현하기 위한 빈 PATH 디렉터리 + jq가 있는 환경을 재현하기 위한 shim
mkdir -p "$WORK/nojq" "$WORK/withjq"
if command -v jq >/dev/null 2>&1; then
  ln -sf "$(command -v jq)" "$WORK/withjq/jq" 2>/dev/null || cp "$(command -v jq)" "$WORK/withjq/jq"
else
  # 실제 jq가 없으면 python으로 최소 동작(이 훅들이 쓰는 두 표현식)만 흉내낸다
  cat > "$WORK/withjq/jq" <<'SHIM'
#!/bin/bash
EXPR=""
for a in "$@"; do case "$a" in -r) ;; *) EXPR="$a";; esac; done
"$PY" -c "
import json,sys
expr = sys.argv[1]
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
if 'file_path' in expr:
    v = (d.get('tool_input') or {}).get('file_path')
    print(v if v else '')
else:
    print('true' if d.get('stop_hook_active') else 'false')
" "$EXPR"
SHIM
  chmod +x "$WORK/withjq/jq"
fi

# json_input <file_path> — PreToolUse 입력 JSON 생성 (백슬래시를 올바로 이스케이프)
json_input() {
  "$PY" -c "
import json,sys
print(json.dumps({'tool_input': {'file_path': sys.argv[1]}}))
" "$1"
}

# check <설명> <기대exit> <실제exit> [출력] [기대 메시지 접두사]
#
# 5번째 인자를 주면 출력이 그 문구로 시작하는지도 확인한다. 종료 코드만 보면
# **맞는 답이 틀린 이유로 나와도 통과**한다. 실제로 그런 적이 있다 — 검증 흔적이
# 전혀 없을 때 "흔적 없음" 분기가 아니라 "낡음" 분기가 돌면서 exit 2가 나왔고,
# 코드만 보던 테스트는 이를 잡지 못했다. 차단 케이스는 분기를 특정할 것.
check() {
  local desc="$1" want="$2" got="$3" out="${4:-}" want_msg="${5:-}"
  if [ "$want" != "$got" ]; then
    FAIL=$((FAIL + 1))
    printf '  FAIL %s — 기대 exit %s, 실제 %s\n' "$desc" "$want" "$got"
    [ -n "$out" ] && printf '       출력: %s\n' "$out"
    return
  fi
  if [ -n "$want_msg" ]; then
    case "$out" in
      "$want_msg"*) ;;
      *)
        FAIL=$((FAIL + 1))
        printf '  FAIL %s — exit는 맞지만 다른 분기가 실행됨\n' "$desc"
        printf '       기대 시작: %s\n       실제 출력: %s\n' "$want_msg" "$out"
        return
        ;;
    esac
  fi
  PASS=$((PASS + 1))
  printf '  ok   %s (exit %s)\n' "$desc" "$got"
}

# ---------------------------------------------------------------------------
# protect-migrations.sh
# ---------------------------------------------------------------------------
MIG_POSIX="$REPO_ROOT/src/main/resources/db/migration"

# 같은 경로의 Windows 표기(C:\Users\...\migration)를 만든다.
# GNU sed 전용 확장(\U)을 쓰지 않는다 — macOS(BSD sed)에서는 조용히 잘못된 값이 나온다.
DRIVE=$(printf '%s' "$MIG_POSIX" | sed -n 's#^/\([a-zA-Z]\)/.*#\1#p')
if [ -n "$DRIVE" ]; then
  DRIVE_UPPER=$(printf '%s' "$DRIVE" | tr 'a-z' 'A-Z')
  REST=$(printf '%s' "$MIG_POSIX" | sed 's#^/[a-zA-Z]/##')
  MIG_WIN=$(printf '%s:/%s' "$DRIVE_UPPER" "$REST" | tr '/' '\\')
  PATH_STYLES="posix win"
else
  # 드라이브 문자가 없는 환경(macOS·Linux)에는 백슬래시 경로가 애초에 오지 않는다
  MIG_WIN="$MIG_POSIX"
  PATH_STYLES="posix"
  SKIPPED="${SKIPPED}- Windows 백슬래시 경로 축: 드라이브 문자 경로가 아니라 건너뜀(Windows에서 실행하면 검사됨)\n"
fi

run_migration_case() {
  local desc="$1" file="$2" expected="$3" jqmode="$4"
  local out rc
  out=$(json_input "$file" | PATH="$WORK/$jqmode:$PATH" bash "$HOOKS_DIR/protect-migrations.sh" 2>&1)
  rc=$?
  check "$desc" "$expected" "$rc" "$out"
}

for JQMODE in withjq nojq; do
  for STYLE in $PATH_STYLES; do
    if [ "$STYLE" = posix ]; then DIR="$MIG_POSIX"; SEP=/; else DIR="$MIG_WIN"; SEP='\'; fi
    echo "protect-migrations [jq=$JQMODE, path=$STYLE]"
    run_migration_case "커밋된 V102 수정 → 차단"          "$DIR${SEP}V102__feed_event.sql"   2 "$JQMODE"
    run_migration_case "커밋된 V1 수정 → 차단"            "$DIR${SEP}V1__create_schemas.sql" 2 "$JQMODE"
    run_migration_case "새 V2 생성 → 허용"                "$DIR${SEP}V2__member.sql"         0 "$JQMODE"
    run_migration_case "새 V103 생성 → 허용"              "$DIR${SEP}V103__ai_extra.sql"     0 "$JQMODE"
    run_migration_case "중복 버전 V102__another → 차단"   "$DIR${SEP}V102__another.sql"      2 "$JQMODE"
    run_migration_case "선행 0 중복 V0102 → 차단"         "$DIR${SEP}V0102__another.sql"     2 "$JQMODE"
    run_migration_case "형식 위반 VX__x.sql → 차단"       "$DIR${SEP}VX__bad.sql"            2 "$JQMODE"
    run_migration_case "migration의 README.md → 통과"     "$DIR${SEP}README.md"              0 "$JQMODE"
    run_migration_case "마이그레이션 외 파일 → 통과"      "$REPO_ROOT/build.gradle"          0 "$JQMODE"
  done
done

# ---------------------------------------------------------------------------
# verify-check.sh — 임시 git 픽스처
# ---------------------------------------------------------------------------
FIX="$WORK/fixture"
mkdir -p "$FIX/src/main/java" "$FIX/config"
cd "$FIX" || exit 1
git init -q .
git config user.email t@t; git config user.name t
: > build.gradle
: > settings.gradle
: > compose.yaml
echo 'class A {}' > src/main/java/A.java
git add -A && git commit -qm init

# run_verify_case <설명> <기대exit> <jqmode> [입력JSON] [기대 메시지 접두사]
run_verify_case() {
  local desc="$1" expected="$2" jqmode="$3" input="${4:-\{\}}" want_msg="${5:-}"
  local out rc
  out=$(printf '%s' "$input" | CLAUDE_PROJECT_DIR="$FIX" PATH="$WORK/$jqmode:$PATH" bash "$HOOKS_DIR/verify-check.sh" 2>&1)
  rc=$?
  check "$desc" "$expected" "$rc" "$out" "$want_msg"
}

# 차단 분기별 메시지 시작 문구 — 어느 분기가 돌았는지 특정하는 용도
MSG_NO_EVIDENCE="소스 변경이 검증되지 않았습니다"
MSG_STALE="테스트 실행 이후 소스가 변경되었습니다"
MSG_FAILED_TEST="build/test-results에 실패한 테스트가 있습니다"
MSG_CHECKSTYLE="Checkstyle 위반이 남아 있습니다"

for JQMODE in withjq nojq; do
  echo "verify-check [jq=$JQMODE]"
  cd "$FIX" || exit 1
  git checkout -q -- . 2>/dev/null
  rm -rf build

  run_verify_case "변경 없음 → 통과" 0 "$JQMODE"

  echo 'class A { int x; }' > src/main/java/A.java
  # 검증 흔적이 아예 없는 상태 — 반드시 "흔적 없음" 분기여야 한다.
  # (xargs 빈 입력 버그가 있으면 여기서 "낡음" 분기가 돌면서 exit 2만 맞고 통과했다)
  run_verify_case "미검증 src 변경 → 차단" 2 "$JQMODE" '{}' "$MSG_NO_EVIDENCE"
  run_verify_case "stop_hook_active 루프 가드 → 통과" 0 "$JQMODE" '{"stop_hook_active":true}'

  mkdir -p build/test-results
  echo '<testsuite failures="0" errors="0"/>' > build/test-results/A.xml
  touch build/test-results/A.xml
  run_verify_case "검증 흔적 있음 → 통과" 0 "$JQMODE"

  sleep 1
  touch compose.yaml
  run_verify_case "검증 후 compose.yaml 변경 → 차단(재검증)" 2 "$JQMODE" '{}' "$MSG_STALE"

  sleep 1
  touch build/test-results/A.xml
  echo '<testsuite failures="2" errors="0"/>' > build/test-results/A.xml
  run_verify_case "실패한 테스트 결과 → 차단" 2 "$JQMODE" '{}' "$MSG_FAILED_TEST"

  echo '<testsuite failures="0" errors="0"/>' > build/test-results/A.xml
  mkdir -p build/reports/checkstyle
  echo '<file><error line="1"/></file>' > build/reports/checkstyle/main.xml
  run_verify_case "checkstyle 위반 → 차단" 2 "$JQMODE" '{}' "$MSG_CHECKSTYLE"
  rm -rf build/reports
done

echo
if [ -n "$SKIPPED" ]; then
  echo "건너뛴 검사(이 환경에서 해당 없음):"
  printf '%b' "$SKIPPED"
fi
echo "통과 $PASS · 실패 $FAIL"
[ "$FAIL" -eq 0 ]
