#!/bin/bash
# 훅 입력(JSON) 파싱 공용 함수 — protect-migrations.sh · verify-check.sh 에서 source 한다.
#
# Windows Git Bash 기본 설치에는 jq가 없다. jq를 그대로 호출하면 훅이 조용히 무력화되므로
# jq → sed 폴백 순으로 파싱한다. 파싱 실패는 빈 값으로 돌려주고, 그때의 판단은 호출한 훅이 한다.

# hook_file_path <json> — .tool_input.file_path (없으면 빈 문자열)
hook_file_path() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r '.tool_input.file_path // empty' 2>/dev/null
    return
  fi
  # 폴백: "file_path": "<값>" 의 값만 뽑고 JSON 이스케이프(\\ · \")를 해제
  printf '%s' "$json" \
    | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\(\([^"\\]\|\\.\)*\)".*/\1/p' \
    | sed 's/\\\(.\)/\1/g' \
    | head -1
}

# hook_stop_hook_active <json> — .stop_hook_active 가 true면 0, 아니면 1
hook_stop_hook_active() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    [ "$(printf '%s' "$json" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ]
    return
  fi
  printf '%s' "$json" | grep -qE '"stop_hook_active"[[:space:]]*:[[:space:]]*true'
}

# hook_normalize_path <경로> — Windows 백슬래시 경로를 슬래시로 정규화
# Windows에서 Claude Code는 file_path를 'C:\...\db\migration\V1__x.sql' 형태로 넘긴다.
# 정규화하지 않으면 슬래시 기준 경로 매칭이 전부 빗나가 훅이 통째로 무력화된다.
hook_normalize_path() {
  printf '%s' "$1" | sed 's#\\#/#g; s#//*#/#g'
}
