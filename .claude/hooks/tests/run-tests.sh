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
python -c "
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
  python -c "
import json,sys
print(json.dumps({'tool_input': {'file_path': sys.argv[1]}}))
" "$1"
}

# check <설명> <기대exit> <실제exit> [출력]
check() {
  if [ "$2" = "$3" ]; then
    PASS=$((PASS + 1))
    printf '  ok   %s (exit %s)\n' "$1" "$3"
  else
    FAIL=$((FAIL + 1))
    printf '  FAIL %s — 기대 exit %s, 실제 %s\n' "$1" "$2" "$3"
    [ -n "${4:-}" ] && printf '       출력: %s\n' "$4"
  fi
}

# ---------------------------------------------------------------------------
# protect-migrations.sh
# ---------------------------------------------------------------------------
MIG_POSIX="$REPO_ROOT/src/main/resources/db/migration"
# 같은 경로의 Windows 표기 (C:\Users\...\migration). Windows가 아니면 POSIX 경로 그대로 쓴다.
MIG_WIN=$(printf '%s' "$MIG_POSIX" | sed 's#^/\([a-zA-Z]\)/#\U\1:/#' | tr '/' '\\')

run_migration_case() {
  local desc="$1" file="$2" expected="$3" jqmode="$4"
  local out rc
  out=$(json_input "$file" | PATH="$WORK/$jqmode:$PATH" bash "$HOOKS_DIR/protect-migrations.sh" 2>&1)
  rc=$?
  check "$desc" "$expected" "$rc" "$out"
}

for JQMODE in withjq nojq; do
  for STYLE in posix win; do
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

run_verify_case() {
  local desc="$1" expected="$2" jqmode="$3" input="${4:-\{\}}"
  local out rc
  out=$(printf '%s' "$input" | CLAUDE_PROJECT_DIR="$FIX" PATH="$WORK/$jqmode:$PATH" bash "$HOOKS_DIR/verify-check.sh" 2>&1)
  rc=$?
  check "$desc" "$expected" "$rc" "$out"
}

for JQMODE in withjq nojq; do
  echo "verify-check [jq=$JQMODE]"
  cd "$FIX" || exit 1
  git checkout -q -- . 2>/dev/null
  rm -rf build

  run_verify_case "변경 없음 → 통과" 0 "$JQMODE"

  echo 'class A { int x; }' > src/main/java/A.java
  run_verify_case "미검증 src 변경 → 차단" 2 "$JQMODE"
  run_verify_case "stop_hook_active 루프 가드 → 통과" 0 "$JQMODE" '{"stop_hook_active":true}'

  mkdir -p build/test-results
  echo '<testsuite failures="0" errors="0"/>' > build/test-results/A.xml
  touch build/test-results/A.xml
  run_verify_case "검증 흔적 있음 → 통과" 0 "$JQMODE"

  sleep 1
  touch compose.yaml
  run_verify_case "검증 후 compose.yaml 변경 → 차단(재검증)" 2 "$JQMODE"

  sleep 1
  touch build/test-results/A.xml
  echo '<testsuite failures="2" errors="0"/>' > build/test-results/A.xml
  run_verify_case "실패한 테스트 결과 → 차단" 2 "$JQMODE"

  echo '<testsuite failures="0" errors="0"/>' > build/test-results/A.xml
  mkdir -p build/reports/checkstyle
  echo '<file><error line="1"/></file>' > build/reports/checkstyle/main.xml
  run_verify_case "checkstyle 위반 → 차단" 2 "$JQMODE"
  rm -rf build/reports
done

echo
echo "통과 $PASS · 실패 $FAIL"
[ "$FAIL" -eq 0 ]
