#!/bin/bash
# Stop — 코드 변경이 있으면 `./gradlew clean check --no-daemon` 검증 없이 완료하지 못하게 차단
# 근거: CLAUDE.md 8번, docs/development/testing-conventions.md
# 훅 자체는 빌드를 돌리지 않는다(느림). 검증 흔적(build/test-results, checkstyle 리포트)만 빠르게 확인하고,
# 없거나 소스보다 오래됐으면 exit 2로 Claude에게 실행을 요구한다.
set -u

# shellcheck source=lib/hook-input.sh
. "$(dirname "$0")/lib/hook-input.sh"

INPUT=$(cat)
# 무한 루프 방지: 이 훅의 차단으로 이미 계속된 턴이면 통과
if hook_stop_hook_active "$INPUT"; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(pwd)}" || exit 0
[ -f build.gradle ] || exit 0

# 검증이 필요한 변경(소스·빌드 설정) 목록 — 변경 감지와 최신성 검사가 같은 목록을 봐야 한다
VERIFY_PATHS="src build.gradle settings.gradle config compose.yaml"

# 커밋되지 않은 변경이 없으면 통과 — 문서만 고친 세션은 check 불필요
# (알려진 한계: 검증 없이 커밋까지 마치고 종료하면 여기서 놓친다. 그 경우는 CI backend-ci/check가 잡는다.)
CHANGES=$(git status --porcelain -- $VERIFY_PATHS 2>/dev/null)
[ -z "$CHANGES" ] && exit 0

BLOCK_MSG="소스 변경이 검증되지 않았습니다. Docker가 실행 중인지 확인한 뒤 './gradlew clean check --no-daemon'을 실행해 전체 통과를 확인하고 완료하세요. (CLAUDE.md 8번)"

# `find ... | xargs ls -t`를 쓰면 안 된다 — 입력이 비어도 xargs가 `ls -t`를 인자 없이
# 한 번 실행해 현재 디렉터리 목록을 뱉는다. 그러면 "검증 흔적이 전혀 없음" 분기가
# 영영 실행되지 않고, 엉뚱한 파일의 mtime과 소스를 비교하다 조용히 통과한다.
# `-exec ... +`는 매치가 없으면 아예 실행되지 않는다.
NEWEST_RESULT=$(find build/test-results -type f -name '*.xml' -exec ls -t {} + 2>/dev/null | head -1)
if [ -z "$NEWEST_RESULT" ]; then
  echo "$BLOCK_MSG" >&2
  exit 2
fi

# 마지막 테스트 실행 이후 소스가 또 바뀌었으면 재검증 필요
STALE=$(find $VERIFY_PATHS -type f -newer "$NEWEST_RESULT" 2>/dev/null | head -1)
if [ -n "$STALE" ]; then
  echo "테스트 실행 이후 소스가 변경되었습니다($STALE). $BLOCK_MSG" >&2
  exit 2
fi

# 테스트 실패가 남아 있으면 차단
if grep -rlE 'failures="[1-9]|errors="[1-9]' build/test-results >/dev/null 2>&1; then
  echo "build/test-results에 실패한 테스트가 있습니다. 실패를 고치고 './gradlew clean check --no-daemon' 전체 통과 후 완료하세요." >&2
  exit 2
fi

# checkstyle 위반이 남아 있으면 차단 (check는 maxWarnings=0)
if [ -d build/reports/checkstyle ] && grep -l '<error ' build/reports/checkstyle/*.xml >/dev/null 2>&1; then
  echo "Checkstyle 위반이 남아 있습니다(build/reports/checkstyle). 위반을 고치고 './gradlew clean check --no-daemon' 통과 후 완료하세요." >&2
  exit 2
fi

exit 0
