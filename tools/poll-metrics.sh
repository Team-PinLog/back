#!/usr/bin/env bash
# actuator prometheus를 5초 간격으로 긁어 CSV로 남긴다.
# 사용: bash tools/poll-metrics.sh <출력.csv>   (SIGTERM으로 종료)
#
# tomcat_threads_*는 현재 노출되지 않는다(Boot 3 mbean registry 미활성) —
# 스레드는 jvm_threads_live_threads로 대체한다. 부재 자체가 개선 티켓 후보다.
set -uo pipefail

OUT="${1:?출력 CSV 경로 필요}"
BASE="${BASE_URL:-http://localhost:8080/api/core}"

echo "epoch,hikari_active,hikari_idle,hikari_pending,jvm_threads,heap_used_bytes" > "$OUT"

while true; do
  BODY=$(curl -sf --max-time 4 "$BASE/actuator/prometheus" || true)
  if [ -n "$BODY" ]; then
    # 여러 heap 영역(Eden·Old·Survivor)을 합산한다.
    printf '%s\n' "$BODY" | awk -v ts="$(date +%s)" '
      /^hikaricp_connections_active\{/  { active = $NF }
      /^hikaricp_connections_idle\{/    { idle = $NF }
      /^hikaricp_connections_pending\{/ { pending = $NF }
      /^jvm_threads_live_threads/       { threads = $NF }
      /^jvm_memory_used_bytes\{area="heap"/ { heap += $NF }
      END { printf "%s,%s,%s,%s,%s,%.0f\n", ts, active, idle, pending, threads, heap }
    ' >> "$OUT"
  fi
  sleep 5
done
