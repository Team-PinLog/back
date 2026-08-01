#!/usr/bin/env bash
# 부하 실행 단위 오케스트레이터.
# 사용: bash tools/run-load.sh <read|write|mixed> <smoke|average|stress|spike>
#       bash tools/run-load.sh all     (유효 조합 10개 전부, 순차)
#
# 실행 순서: 전제 확인 → 불변식(전) → 회원 풀 → 토큰 → 폴러 → k6 → 폴러 종료
#           → 불변식(후) → 판정 → 정리(trap)
# back_violations 기준선: 4696. 전후가 다르면 그 실행은 실패다 — 부하 중 정합 훼손이
# 이 티켓의 최우선 발견이 된다.
set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
K6_BIN="${K6_BIN:-/c/Program Files/k6/k6.exe}"
PG="${PG_CONTAINER:-back-postgres-1}"
BASE_URL="${BASE_URL:-http://localhost:8080/api/core}"
PSQL=(docker exec -i "$PG" psql -U pinlog -d pinlog)
BASELINE=4696

SERIES="${1:?계열(read|write|mixed) 또는 all}"
PROFILE="${2:-}"

if [ "$SERIES" = "all" ]; then
  STATUS=0
  for combo in \
    "read smoke" "read average" "read stress" "read spike" \
    "write smoke" "write average" "write stress" "write spike" \
    "mixed average" "mixed stress"; do
    bash "$0" $combo || STATUS=1
  done
  exit "$STATUS"
fi

case "$SERIES" in read|write|mixed) ;; *) echo "알 수 없는 계열: $SERIES" >&2; exit 2 ;; esac
[ -n "$PROFILE" ] || { echo "프로파일 필요" >&2; exit 2; }

RUN_DIR="$HERE/artifacts/load/$SERIES-$PROFILE"
mkdir -p "$RUN_DIR"
LOG="$RUN_DIR/k6.log"

log() { printf '%s\n' "$*"; }
fail_precondition() { log "[전제 실패] $*"; exit 2; }

# --- 전제 ---
[ -x "$K6_BIN" ] || fail_precondition "k6 없음: $K6_BIN"
curl -sf -o /dev/null --max-time 5 "$BASE_URL/actuator/health" || fail_precondition "앱이 응답하지 않는다"
docker exec "$PG" pg_isready -U pinlog -d pinlog > /dev/null 2>&1 || fail_precondition "Postgres 미기동"

back_violations() {
  "${PSQL[@]}" -t -A -f - < "$HERE/sql/verify-invariants.sql" | tail -1 | cut -d'|' -f1
}

BEFORE=$(back_violations)
printf '%s\n' "$BEFORE" > "$RUN_DIR/invariants-before.txt"
[ "$BEFORE" = "$BASELINE" ] || log "[경고] 실행 전 back_violations=$BEFORE (기준선 $BASELINE 아님) — 전후 비교로만 판정"

# --- 회원 풀 (쓰기·혼합만) ---
POOL_IDS=""; SHARED_MEMBER=""; SHARED_RECORD=""
cleanup() {
  local rc=$?
  if [ -n "$POOL_IDS" ]; then
    "${PSQL[@]}" -q -v ON_ERROR_STOP=1 -v ids="$POOL_IDS,$SHARED_MEMBER" \
      -f - < "$HERE/tools/teardown-load-members.sql" \
      && log "회원 풀 정리 완료" || log "[경고] 회원 풀 정리 실패 — 수동 확인 필요: $POOL_IDS"
  fi
  [ -n "${POLLER_PID:-}" ] && kill "$POLLER_PID" 2>/dev/null
  return 0
}
trap cleanup EXIT

if [ "$SERIES" != "read" ]; then
  # 프로파일 최대 VU만큼. profiles.js와 값이 어긋나지 않도록 한 곳(여기)에만 적는다.
  case "$PROFILE" in
    smoke) N=1 ;; average) N=20 ;; stress) N=100 ;; spike) N=150 ;;
    *) fail_precondition "알 수 없는 프로파일: $PROFILE" ;;
  esac
  OUT=$("${PSQL[@]}" -t -A -v ON_ERROR_STOP=1 -v member_count="$N" \
    -f - < "$HERE/tools/setup-load-members.sql")
  SHARED_MEMBER=$(printf '%s\n' "$OUT" | grep '^shared:' | cut -d: -f2)
  SHARED_RECORD=$(printf '%s\n' "$OUT" | grep '^record:' | cut -d: -f2)
  POOL_IDS=$(printf '%s\n' "$OUT" | grep -v ':' | paste -sd, -)
  log "회원 풀 $N명 + 공유 $SHARED_MEMBER (record $SHARED_RECORD)"
  # shellcheck disable=SC2086
  bash "$HERE/tools/mint-tokens.sh" $(printf '%s' "$POOL_IDS" | tr ',' ' ') "$SHARED_MEMBER" > /dev/null
else
  bash "$HERE/tools/mint-tokens.sh" 1 2792 > /dev/null
fi

# --- 폴러 + k6 ---
bash "$HERE/tools/poll-metrics.sh" "$RUN_DIR/metrics.csv" & POLLER_PID=$!

(
  cd "$HERE"
  PROFILE="$PROFILE" POOL_IDS="$POOL_IDS" \
  SHARED_MEMBER="$SHARED_MEMBER" SHARED_RECORD="$SHARED_RECORD" \
    "$K6_BIN" run --summary-export "$RUN_DIR/summary.json" "k6/load-$SERIES.js"
) > "$LOG" 2>&1
K6_EXIT=$?
kill "$POLLER_PID" 2>/dev/null; POLLER_PID=""

# --- 불변식(후)·판정 ---
AFTER=$(back_violations)
printf '%s\n' "$AFTER" > "$RUN_DIR/invariants-after.txt"

STATUS=0
[ "$K6_EXIT" -ne 0 ] && { log "[실패] k6 종료 코드 $K6_EXIT"; STATUS=1; }
[ "$BEFORE" != "$AFTER" ] && { log "[실패] back_violations 변동: $BEFORE → $AFTER"; STATUS=1; }
log "$SERIES-$PROFILE: k6=$K6_EXIT, 정합 $BEFORE→$AFTER, 산출물 $RUN_DIR"
exit "$STATUS"
