#!/usr/bin/env bash
# 전체 검증을 한 번에 돌린다.
#   토큰 발급 → 전용 회원 생성 → k6 27개 전수 → SQL 지목 검증 → SQL 전역 불변식 → 리포트 → 정리
#
# 종료 코드:
#   0  계약 검사와 지목 검증이 모두 통과. 전역 불변식 위반은 리포트에만 남는다
#   1  k6 계약 검사 또는 지목 검증 실패
#   2  전제 조건 미충족(스택이 안 떠 있음, k6 없음 등)
set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"          # back/loadtest
ART="$HERE/artifacts"
K6_BIN="${K6_BIN:-/c/Program Files/k6/k6.exe}"
PG="${PG_CONTAINER:-back-postgres-1}"
BASE_URL="${BASE_URL:-http://localhost:8080/api/core}"
PSQL=(docker exec -i "$PG" psql -U pinlog -d pinlog)
export PYTHONUTF8=1                                # Windows 로캘(cp949)에서도 UTF-8로 열고 쓴다

mkdir -p "$ART"
REPORT="$ART/report.txt"
: > "$REPORT"

log() { printf '%s\n' "$*" | tee -a "$REPORT"; }
fail_precondition() { log "[전제 실패] $*"; exit 2; }

# --- 0. 전제 확인 ---
log "=== 전제 확인 ==="
[ -x "$K6_BIN" ] || fail_precondition "k6를 찾을 수 없다: $K6_BIN (K6_BIN으로 넘겨라)"
log "k6: $("$K6_BIN" version)"

docker exec "$PG" pg_isready -U pinlog -d pinlog > /dev/null 2>&1 \
  || fail_precondition "Postgres 컨테이너($PG)가 준비되지 않았다"
log "Postgres: 준비됨"

curl -sf -o /dev/null --max-time 5 "$BASE_URL/actuator/health" \
  || fail_precondition "$BASE_URL 이 응답하지 않는다. 앱을 JWT_PRIVATE_KEY와 함께 띄워라"
log "애플리케이션: 응답함"

# --- 1. 전용 테스트 회원 ---
log ""
log "=== 전용 테스트 회원 생성 ==="
TEST_MEMBER_ID=$("${PSQL[@]}" -t -A -v ON_ERROR_STOP=1 -f - \
  < "$HERE/tools/setup-test-member.sql" | tr -d '[:space:]')
case "$TEST_MEMBER_ID" in
  ''|*[!0-9]*) fail_precondition "테스트 회원 생성 실패: '$TEST_MEMBER_ID'" ;;
esac
log "member_id=$TEST_MEMBER_ID"

GOLDEN_BEFORE=$("${PSQL[@]}" -t -A -c "
  select
    (select count(*) from core.member     where id <= 6)  || '/' ||
    (select count(*) from core.record     where id <= 25) || '/' ||
    (select count(*) from core.collection where id <= 7)")
log "골든 행 수(member<=6/record<=25/collection<=7): $GOLDEN_BEFORE"

cleanup() {
  log ""
  log "=== 정리 ==="
  "${PSQL[@]}" -v ON_ERROR_STOP=1 -v member_id="$TEST_MEMBER_ID" -f - \
    < "$HERE/tools/teardown-test-member.sql" >> "$REPORT" 2>&1 \
    && log "전용 회원 $TEST_MEMBER_ID 정리 완료" \
    || log "[경고] 전용 회원 정리 실패 — 수동 확인이 필요하다"

  GOLDEN_AFTER=$("${PSQL[@]}" -t -A -c "
    select
      (select count(*) from core.member     where id <= 6)  || '/' ||
      (select count(*) from core.record     where id <= 25) || '/' ||
      (select count(*) from core.collection where id <= 7)")
  if [ "$GOLDEN_BEFORE" = "$GOLDEN_AFTER" ]; then
    log "골든 행 수 유지: $GOLDEN_AFTER"
  else
    log "[실패] 골든 행 수가 바뀌었다: $GOLDEN_BEFORE → $GOLDEN_AFTER"
  fi
}
trap cleanup EXIT

# --- 2. 토큰 발급 ---
log ""
log "=== 토큰 발급 ==="
BASE_URL="$BASE_URL" bash "$HERE/tools/mint-tokens.sh" 1 2 3 2792 "$TEST_MEMBER_ID" \
  | tee -a "$REPORT"

# --- 3. k6 전수 실행 ---
log ""
log "=== k6 27개 전수 ==="
K6_LOG="$ART/k6.log"
(
  cd "$HERE" || exit 1
  TEST_MEMBER_ID="$TEST_MEMBER_ID" BASE_URL="$BASE_URL" \
    "$K6_BIN" run --summary-trend-stats="avg,min,med,p(90),p(95),p(99),count" \
      --summary-export "$ART/summary.json" k6/functional.js
) > "$K6_LOG" 2>&1
K6_EXIT=$?
cat "$K6_LOG" >> "$REPORT"
log "k6 종료 코드: $K6_EXIT"

# --- 4. 만진 id 추출 ---
log ""
log "=== 만진 id 추출 ==="
# recorder.js가 '##TOUCHED## {json}' 한 줄을 흘린다. k6는 SQL도 파일 쓰기도 못 하므로
# 표준출력이 유일한 통로다.
TOUCHED_LINE=$(grep -o '##TOUCHED##.*' "$K6_LOG" | tail -1)
if [ -z "$TOUCHED_LINE" ]; then
  log "[실패] k6 출력에 ##TOUCHED## 표식이 없다. 시나리오가 emit()에 도달하지 못했다"
  exit 1
fi
TOUCHED_JSON="${TOUCHED_LINE#\#\#TOUCHED\#\# }"
# k6는 콘솔 로그를 logfmt로 감싸 msg="..." 안의 내부 따옴표를 escape하고 뒤에
# ` source=console`을 붙인다. 그 감싸기를 벗겨내야 유효한 JSON이 된다.
TOUCHED_JSON="${TOUCHED_JSON%\" source=console}"
TOUCHED_JSON="${TOUCHED_JSON//\\\"/\"}"
printf '%s' "$TOUCHED_JSON" > "$ART/touched.json"
log "touched.json 기록: $(wc -c < "$ART/touched.json") 바이트"

MISSED=$(python -c "
import json, sys
d = json.load(open(sys.argv[1]))
print(len(d['missedEndpoints']), ','.join(d['missedEndpoints']))
" "$ART/touched.json")
log "미호출 엔드포인트: $MISSED"

# --- 5. 지목 검증 ---
log ""
log "=== 지목 검증 (verify-by-id.sql) ==="
TOUCHED_ARRAY=$(python -c "
import json, sys
print(json.dumps(json.load(open(sys.argv[1]))['touched'], ensure_ascii=False))
" "$ART/touched.json")
"${PSQL[@]}" -v ON_ERROR_STOP=1 -v touched="$TOUCHED_ARRAY" -f - \
  < "$HERE/sql/verify-by-id.sql" 2>&1 | tee -a "$REPORT"
ID_EXIT=${PIPESTATUS[0]}

ID_VIOLATIONS=$(grep -A3 '=== 지목 검증 위반 건수 ===' "$REPORT" | tail -1 | tr -d '[:space:]')
log "지목 검증 위반: ${ID_VIOLATIONS:-?}"

# --- 6. 전역 불변식 ---
log ""
log "=== 전역 불변식 (verify-invariants.sql) ==="
"${PSQL[@]}" -v ON_ERROR_STOP=1 -f - \
  < "$HERE/sql/verify-invariants.sql" 2>&1 | tee -a "$REPORT"

# --- 7. 지연 요약 ---
log ""
log "=== 엔드포인트 부류별 지연 ==="
if [ -f "$ART/summary.json" ]; then
  python -c "
import json, sys
data = json.load(open(sys.argv[1]))
print(f\"{'부류':<10}{'p50':>10}{'p95':>10}{'p99':>10}{'건수':>8}\")
for name, metric in sorted(data.get('metrics', {}).items()):
    if name == 'http_req_duration':
        kind = '전체'
    elif name.startswith('http_req_duration{kind:') and name.endswith('}'):
        kind = name[len('http_req_duration{kind:'):-1]
    else:
        continue
    print(f\"{kind:<10}{metric.get('med', 0):>10.1f}{metric.get('p(95)', 0):>10.1f}\"
          f\"{metric.get('p(99)', 0):>10.1f}{int(metric.get('count', 0)):>8}\")
" "$ART/summary.json" | tee -a "$REPORT"
else
  log "[경고] summary.json이 없다"
fi

# --- 8. 판정 ---
log ""
log "=== 판정 ==="
STATUS=0
if [ "$K6_EXIT" -ne 0 ]; then
  log "[실패] k6 계약 검사 또는 임계값 위반"
  STATUS=1
fi
if [ "${ID_VIOLATIONS:-1}" != "0" ]; then
  log "[실패] 지목 검증 위반 ${ID_VIOLATIONS}건"
  STATUS=1
fi
if [ "$ID_EXIT" -ne 0 ]; then
  log "[실패] 지목 검증 SQL 실행 오류"
  STATUS=1
fi
log "전역 불변식 위반은 종료 코드에 반영하지 않는다 — 리포트의 '소유별 합계'를 볼 것"
log "리포트: $REPORT"
[ "$STATUS" -eq 0 ] && log "결과: 통과" || log "결과: 실패"
exit "$STATUS"
