#!/usr/bin/env bash
# ─── Axolotl JWT Token Helper for curl ─────────────────────────────────
#
# Usage:
#   source scripts/token.sh        # exports $TOKEN and $CURL_HEADER
#   curl -s -H "$CURL_HEADER" http://localhost:8082/api/schemas
#
# Caches token in ~/.axolotl/token, reuses until expiry (23h).
# Falls back to tech/tech auth if no env vars set.
#
# ────────────────────────────────────────────────────────────────────────

set -euo pipefail

AXOLOTL_URL="${AXOLOTL_URL:-http://localhost:8082}"
AXOLOTL_USER="${AXOLOTL_USER:-tech}"
AXOLOTL_PASS="${AXOLOTL_PASS:-tech}"
TOKEN_DIR="${HOME}/.axolotl"
TOKEN_FILE="${TOKEN_DIR}/token"

# ─── helpers ──────────────────────────────────────────────────────────

_token_valid() {
  [[ -f "$TOKEN_FILE" ]] || return 1
  local ts expiry
  ts=$(stat -f "%m" "$TOKEN_FILE" 2>/dev/null || stat -c "%Y" "$TOKEN_FILE" 2>/dev/null)
  expiry=$((ts + 82800))  # 23 hours — refresh before server TTL of 24h
  [[ $(date +%s) -lt $expiry ]]
}

_fetch_token() {
  mkdir -p "$TOKEN_DIR"
  local json curl_exit
  json=$(curl -s --fail -X POST "${AXOLOTL_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${AXOLOTL_USER}\",\"password\":\"${AXOLOTL_PASS}\"}")
  curl_exit=$?
  if [[ $curl_exit -ne 0 ]]; then
    echo "[token.sh] ERROR: failed to get token (curl exit $curl_exit, url=$AXOLOTL_URL)" >&2
    echo "" > "$TOKEN_FILE"
    return 1
  fi
  local token
  token=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
  if [[ -z "$token" ]]; then
    echo "[token.sh] ERROR: login response did not contain a token" >&2
    echo "" > "$TOKEN_FILE"
    return 1
  fi
  echo "$token" > "$TOKEN_FILE"
}

# ─── main ─────────────────────────────────────────────────────────────

if ! _token_valid; then
  _fetch_token
fi

TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null || echo "")

if [[ -z "$TOKEN" ]]; then
  echo "[token.sh] ERROR: no token available. Check backend at $AXOLOTL_URL" >&2
  return 1 2>/dev/null || exit 1
fi

export TOKEN
export CURL_HEADER="Authorization: Bearer ${TOKEN}"
