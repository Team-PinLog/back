#!/usr/bin/env bash
# 로컬 검증용 access_token 뭉치를 발급한다.
# 사용: bash tools/mint-tokens.sh <member_id>...
#
# k6는 RSA 서명을 못 하므로(k6/crypto에 RSA sign이 없다) 토큰은 반드시 k6 밖에서 만들어
# 파일로 넘긴다. openssl만 쓴다.
#
# kid는 넣지 않는다 — 서버 JWK의 thumbprint kid와 어긋나면 키 선택에서 탈락한다.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"          # back/loadtest
ENV_FILE="${ENV_FILE:-$HERE/../.env}"
BASE_URL="${BASE_URL:-http://localhost:8080/api/core}"
TTL="${TOKEN_TTL:-1800}"                           # application.yml의 access-token-ttl(30m)
ART="$HERE/artifacts"
PEM="$ART/jwt-private.pem"
OUT="$ART/tokens.json"

[ "$#" -gt 0 ] || { echo "member_id를 하나 이상 넘겨야 한다" >&2; exit 2; }
mkdir -p "$ART"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# .env의 JWT_PRIVATE_KEY는 헤더를 포함한 PEM을 리터럴 \n으로 이어붙인 한 줄이다.
# 그 \n을 진짜 줄바꿈으로 되돌리면 그대로 유효한 PEM이 된다.
write_pem() {
  local raw
  raw=$(sed -n 's/^JWT_PRIVATE_KEY=//p' "$ENV_FILE" | head -1)
  raw="${raw%\"}"; raw="${raw#\"}"
  raw="${raw%\'}"; raw="${raw#\'}"
  [ -n "$raw" ] || { echo "JWT_PRIVATE_KEY가 없다: $ENV_FILE" >&2; return 1; }

  printf '%s\n' "${raw//\\n/$'\n'}" > "$PEM"
  chmod 600 "$PEM"

  if openssl pkey -in "$PEM" -noout 2>/dev/null; then
    return 0
  fi

  # 헤더 없는 한 줄 base64로 저장돼 있으면 다시 감싼다.
  local body
  body=$(tr -d ' \t\r\n' < "$PEM" \
    | sed -e 's/-----BEGINPRIVATEKEY-----//' -e 's/-----ENDPRIVATEKEY-----//')
  {
    echo '-----BEGIN PRIVATE KEY-----'
    printf '%s' "$body" | fold -w 64
    echo
    echo '-----END PRIVATE KEY-----'
  } > "$PEM"
  chmod 600 "$PEM"
  openssl pkey -in "$PEM" -noout
}

mint() {
  local member_id="$1" now jti header payload signing_input sig

  # 숫자가 아니면 거절한다. member_id는 JWT sub와 tokens.json 키에 그대로 들어가므로
  # 따옴표나 역슬래시가 섞이면 JSON이 깨지고, 그 파일을 읽는 k6 open()과 python json.load가
  # 죽는다. 그런데도 스크립트는 "발급 완료"를 찍어 원인이 발급 단계라는 단서가 남지 않는다.
  case "$member_id" in
    ''|*[!0-9]*) echo "member_id는 숫자여야 한다: $member_id" >&2; exit 2 ;;
  esac

  now=$(date +%s)
  jti=$(openssl rand -hex 16)
  header='{"alg":"RS256","typ":"JWT"}'
  payload=$(printf '{"sub":"%s","iss":"pinlog","iat":%s,"exp":%s,"jti":"%s","token_use":"access"}' \
    "$member_id" "$now" "$((now + TTL))" "$jti")
  signing_input="$(printf '%s' "$header" | b64url).$(printf '%s' "$payload" | b64url)"
  sig=$(printf '%s' "$signing_input" | openssl dgst -sha256 -sign "$PEM" -binary | b64url)
  printf '%s.%s' "$signing_input" "$sig"
}

write_pem

{
  printf '{\n  "baseUrl": "%s",\n  "ttl": %s,\n  "tokens": {\n' "$BASE_URL" "$TTL"
  sep=''
  for member_id in "$@"; do
    printf '%s    "%s": "%s"' "$sep" "$member_id" "$(mint "$member_id")"
    sep=$',\n'
  done
  printf '\n  }\n}\n'
} > "$OUT"

echo "발급 완료: $OUT ($# 명)"
