#!/bin/bash
# PreToolUse(Edit|Write) — Flyway 마이그레이션 불변성 보호 (팀 공용)
# 근거: docs/development/database-conventions.md — "이미 적용된 migration은 절대로 수정하지 않습니다."
#
# 검사 두 가지:
#   1. 이미 커밋된 마이그레이션 파일 수정 차단 (커밋됨 = 적용된 것으로 간주)
#   2. 이미 존재하는 버전 번호로 새 파일 생성 차단 (Flyway 중복 버전 오류를 조기에 차단)
#
# 소유 구간(V2~V99 백엔드, V100~V199 AI)은 훅으로 강제하지 않는다 — 문서와 PR 리뷰가 담당.
set -u

# shellcheck source=lib/hook-input.sh
. "$(dirname "$0")/lib/hook-input.sh"

INPUT=$(cat)
# Windows에서는 file_path가 백슬래시 경로로 오므로 반드시 정규화한 뒤 매칭한다.
FILE_PATH=$(hook_normalize_path "$(hook_file_path "$INPUT")")

[ -z "$FILE_PATH" ] && exit 0
case "$FILE_PATH" in
  */src/main/resources/db/migration/*) ;;
  *) exit 0 ;;
esac

BASENAME=$(basename "$FILE_PATH")
# 마이그레이션 디렉토리의 README 등 SQL 외 파일은 통과
case "$BASENAME" in
  V*__*.sql) ;;
  *) exit 0 ;;
esac

VERSION=$(printf '%s' "$BASENAME" | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p')
if [ -z "$VERSION" ]; then
  echo "마이그레이션 파일명이 V<번호>__<설명>.sql 형식이 아닙니다: $BASENAME" >&2
  exit 2
fi

MIGRATION_DIR=$(dirname "$FILE_PATH")
REPO_ROOT=$(cd "$MIGRATION_DIR" && git rev-parse --show-toplevel 2>/dev/null)

# 1. 이미 커밋된 마이그레이션은 적용된 것으로 간주하고 수정 차단
if [ -n "$REPO_ROOT" ] && git -C "$REPO_ROOT" ls-files --error-unmatch "$FILE_PATH" >/dev/null 2>&1; then
  echo "$BASENAME 은 이미 커밋된 마이그레이션입니다. 적용된 마이그레이션은 수정하지 말고, 자기 소유 구간(백엔드 V2~V99, AI V100~V199)의 다음 번호로 새 마이그레이션을 추가하세요." >&2
  exit 2
fi

# 2. 새 파일이라면, 같은 버전 번호가 이미 있는지 검사 (Flyway는 중복 버전에서 깨짐)
# 선행 0 차이(V2 와 V02)도 Flyway는 같은 버전으로 보므로 문자열이 아니라 10진수로 비교한다.
for existing in "$MIGRATION_DIR"/V*__*.sql; do
  [ -e "$existing" ] || continue
  EXISTING_BASE=$(basename "$existing")
  [ "$EXISTING_BASE" = "$BASENAME" ] && continue
  EXISTING_VERSION=$(printf '%s' "$EXISTING_BASE" | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p')
  [ -z "$EXISTING_VERSION" ] && continue
  if [ "$((10#$EXISTING_VERSION))" -eq "$((10#$VERSION))" ]; then
    echo "V$VERSION 은 이미 $EXISTING_BASE 이 사용 중인 버전입니다. Flyway는 중복 버전에서 실패합니다. 다음 빈 번호를 사용하세요." >&2
    exit 2
  fi
done

exit 0
