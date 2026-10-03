#!/usr/bin/env bash
# Authentication and user CRUD smoke checks. Requires curl and Python 3 (or python).
# Works on Linux/macOS and Windows Git Bash. Usage: bash scripts/test-auth.sh [base-url]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
ADMIN_IDENTIFIER="${ADMIN_IDENTIFIER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
command -v curl >/dev/null 2>&1 || { echo 'curl is required' >&2; exit 2; }
if command -v python3 >/dev/null 2>&1; then PYTHON_BIN=python3
elif command -v python >/dev/null 2>&1; then PYTHON_BIN=python
else echo 'Python 3 (python3 or python) is required' >&2; exit 2
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
RESPONSE_FILE="$TMP_DIR/response.json"

json_object() {
  "$PYTHON_BIN" - "$@" <<'PY'
import json, sys
values = sys.argv[1:]
if len(values) % 2: raise SystemExit("expected key/value pairs")
print(json.dumps(dict(zip(values[::2], values[1::2]))))
PY
}

json_field() {
  "$PYTHON_BIN" - "$RESPONSE_FILE" "$1" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f: value = json.load(f)
for key in sys.argv[2].split("."): value = value[key]
if value is not None: print(value)
PY
}

request() {
  local method="$1" path="$2" token="${3:-}" payload="${4:-}"
  local args=(--silent --show-error --output "$RESPONSE_FILE" --write-out '%{http_code}'
    --request "$method" "$BASE_URL$path" --header 'Accept: application/json')
  [[ -z "$token" ]] || args+=(--header "Authorization: Bearer $token")
  if [[ -n "$payload" ]]; then
    args+=(--header 'Content-Type: application/json' --data "$payload")
  fi
  HTTP_STATUS="$(curl "${args[@]}")"
}

assert_status() {
  local expected="$1" label="$2"
  if [[ "$HTTP_STATUS" != "$expected" ]]; then
    printf 'FAIL %s: expected HTTP %s, got %s\n' "$label" "$expected" "$HTTP_STATUS" >&2
    cat "$RESPONSE_FILE" >&2 || true
    exit 1
  fi
  printf 'PASS %s (HTTP %s)\n' "$label" "$expected"
}

echo "Testing seat-reservation API at $BASE_URL"
request POST /auth/signin "" "$(json_object identifier "$ADMIN_IDENTIFIER" password "$ADMIN_PASSWORD")"
assert_status 200 'Admin sign-in'
[[ "$(json_field role)" == ADMIN ]] || { echo 'Expected ADMIN role' >&2; exit 1; }
ADMIN_TOKEN="$(json_field access_token)"
[[ -n "$ADMIN_TOKEN" ]] || { echo 'Missing admin access_token' >&2; exit 1; }

request GET /users
assert_status 401 'Unauthenticated user-list request is rejected'
request GET /users "$ADMIN_TOKEN"
assert_status 200 'Admin can list users'

SUFFIX="$("$PYTHON_BIN" -c 'import uuid; print(uuid.uuid4().hex[:12])')"
EMAIL="auth-smoke-$SUFFIX@example.test"
UPDATED_EMAIL="auth-smoke-updated-$SUFFIX@example.test"
PASSWORD='smoke-test-pass-123'
UPDATED_PASSWORD='smoke-test-pass-456'
request POST /users "" "$(json_object email "$EMAIL" password "$PASSWORD")"
assert_status 201 'User registration'
[[ "$(json_field role)" == USER ]] || { echo 'Expected USER role' >&2; exit 1; }
USER_ID="$(json_field id)"
[[ -n "$USER_ID" ]] || { echo 'Missing user id' >&2; exit 1; }

request POST /auth/signin "" "$(json_object identifier "$EMAIL" password "$PASSWORD")"
assert_status 200 'User sign-in'
[[ "$(json_field role)" == USER ]] || { echo 'Expected USER role in token response' >&2; exit 1; }
USER_TOKEN="$(json_field access_token)"
request GET /users "$USER_TOKEN"
assert_status 403 'Regular user cannot list all users'
request GET "/users/$USER_ID" "$USER_TOKEN"
assert_status 200 'User can read own account'

request PUT "/users/$USER_ID" "$USER_TOKEN" "$(json_object email "$UPDATED_EMAIL" password "$UPDATED_PASSWORD")"
assert_status 200 'User can update own credentials'
request POST /auth/signin "" "$(json_object identifier "$UPDATED_EMAIL" password "$PASSWORD")"
assert_status 401 'Old password is rejected'
request POST /auth/signin "" "$(json_object identifier "$UPDATED_EMAIL" password "$UPDATED_PASSWORD")"
assert_status 200 'Updated credentials can sign in'
UPDATED_TOKEN="$(json_field access_token)"
request DELETE "/users/$USER_ID" "$UPDATED_TOKEN"
assert_status 204 'User can deactivate own account'
request POST /auth/signin "" "$(json_object identifier "$UPDATED_EMAIL" password "$UPDATED_PASSWORD")"
assert_status 401 'Deactivated user cannot sign in'
echo 'Authentication and user CRUD smoke checks passed.'
