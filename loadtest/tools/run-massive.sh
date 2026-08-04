#!/usr/bin/env bash
# 대량 더미 적재를 한 번에 돌린다 (S15P11A705-283).
#   전제 확인 → 적재(seed-massive.sql) → 검증(verify-massive.sql) → 소요 시간·디스크 기록
#
# 사용:
#   bash loadtest/tools/run-massive.sh                # 회원 10만 → record 1천만
#   MEMBERS=1000 bash loadtest/tools/run-massive.sh   # 축소 실행(약 11만 건)
#
# 종료 코드:
#   0  적재와 검증 모두 통과
#   1  적재 또는 검증 실패
#   2  전제 조건 미충족
#
# **자원 한도 override(compose.bench.yaml)를 얹지 않은 상태에서 돌릴 것.** 인덱스 재생성이
# maintenance_work_mem을 쓰므로 1Gi 한도 안에서는 크게 느려진다. 측정은 적재가 끝난 뒤
# override를 얹어 재기동하고 나서 한다(loadtest/README.md 「대량 벤치」).
set -uo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"          # back/loadtest
ART="$HERE/artifacts"
PG="${PG_CONTAINER:-back-postgres-1}"
MEMBERS="${MEMBERS:-100000}"
PLACES="${PLACES:-200000}"
PSQL=(docker exec -i "$PG" psql -U pinlog -d pinlog)

mkdir -p "$ART"
REPORT="$ART/massive-report.txt"
: > "$REPORT"

log() { printf '%s\n' "$*" | tee -a "$REPORT"; }
fail_precondition() { log "[전제 실패] $*"; exit 2; }

log "=== 전제 확인 ==="
docker exec "$PG" pg_isready -U pinlog -d pinlog > /dev/null 2>&1 \
  || fail_precondition "Postgres 컨테이너($PG)가 준비되지 않았다"

# override가 얹혀 있으면 적재가 그 한도 안에서 기어간다. 미리 끊는다.
MEM_LIMIT=$(docker inspect --format '{{.HostConfig.Memory}}' "$PG" 2>/dev/null || echo 0)
if [ "$MEM_LIMIT" != "0" ]; then
  fail_precondition "컨테이너에 메모리 한도(${MEM_LIMIT}B)가 걸려 있다. 적재는 기본 compose로 돌려라: docker compose up -d postgres"
fi
log "Postgres: 준비됨 (자원 한도 없음)"

# 회원당 record 최대 20,000건과 place 유일성 회전이 전제하는 최소 장소 수
[ "$PLACES" -ge 20000 ] || fail_precondition "PLACES($PLACES)는 20000 이상이어야 한다"

SIZE_BEFORE=$("${PSQL[@]}" -t -A -c "SELECT pg_database_size('pinlog');" | tr -d '[:space:]')
log "적재 전 DB 크기: $(numfmt --to=iec "$SIZE_BEFORE" 2>/dev/null || echo "${SIZE_BEFORE}B")"
log ""

log "=== 적재 (members=$MEMBERS, places=$PLACES) ==="
T0=$(date +%s)
"${PSQL[@]}" -v ON_ERROR_STOP=1 -v members="$MEMBERS" -v places="$PLACES" \
  -f - < "$HERE/tools/seed-massive.sql" 2>&1 | tee -a "$REPORT"
SEED_RC=${PIPESTATUS[0]}
T1=$(date +%s)
if [ "$SEED_RC" -ne 0 ]; then
  log ""
  log "[실패] 적재가 코드 $SEED_RC 로 끝났다"
  exit 1
fi
log ""
log "적재 소요: $((T1 - T0))초"

log ""
log "=== 검증 ==="
# 축소 실행이면 기준도 함께 줄인다(분포 형태는 유지되므로 자릿수 검사는 그대로 성립한다).
MIN_RECORDS=$(( MEMBERS >= 100000 ? 10000000 : 1 ))
"${PSQL[@]}" -v ON_ERROR_STOP=1 -v min_records="$MIN_RECORDS" -v min_members="$MEMBERS" \
  -f - < "$HERE/tools/verify-massive.sql" 2>&1 | tee -a "$REPORT"
VERIFY_RC=${PIPESTATUS[0]}
if [ "$VERIFY_RC" -ne 0 ]; then
  log ""
  log "[실패] 검증이 코드 $VERIFY_RC 로 끝났다"
  exit 1
fi

SIZE_AFTER=$("${PSQL[@]}" -t -A -c "SELECT pg_database_size('pinlog');" | tr -d '[:space:]')
log ""
log "=== 완료 ==="
log "적재 소요: $((T1 - T0))초"
log "DB 크기: $(numfmt --to=iec "$SIZE_BEFORE" 2>/dev/null || echo "${SIZE_BEFORE}B") → $(numfmt --to=iec "$SIZE_AFTER" 2>/dev/null || echo "${SIZE_AFTER}B")"
log "리포트: $REPORT"
log ""
log "다음 단계 — 자원 한도를 얹고 측정:"
log "  docker compose -f compose.yaml -f compose.bench.yaml up -d postgres"
