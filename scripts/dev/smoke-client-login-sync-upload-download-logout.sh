#!/usr/bin/env bash
# Smoke E2E del cliente CLI: login → sync → upload → download → logout.
#
# Bootstrapea opcionalmente un nodo (Postgres docker o H2 in-memory), genera un
# payload determinista, lo sube como usuario autenticado, lo baja, valida bytes
# extremo-a-extremo y cierra sesión. Cubre el flow productivo del cliente.
#
# Uso:
#   ./scripts/dev/smoke-client-login-sync-upload-download-logout.sh
#   USE_H2=true ./scripts/dev/smoke-client-login-sync-upload-download-logout.sh
#   BOOTSTRAP_NODE=false NODE_BASE_URL=http://otro:8081 ./scripts/dev/...
#
# Salidas:
#   exit 0          flow completo + checksums OK
#   exit !=0        algún paso falló (login/upload/download/checksum)
#   artifacts       logs/smoke-downloads/<entry-id>-*

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TEST_USERNAME="${TEST_USERNAME:-tfg_client_test}"
TEST_PASSWORD="${TEST_PASSWORD:-NodeClient2026!}"
NODE_PORT="${NODE_PORT:-8081}"
NODE_BASE_URL="${NODE_BASE_URL:-http://localhost:${NODE_PORT}}"
BOOTSTRAP_NODE="${BOOTSTRAP_NODE:-true}"
USE_H2="${USE_H2:-false}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/logs/smoke-downloads}"
PAYLOAD_PREFIX="${PAYLOAD_PREFIX:-client smoke payload}"

mkdir -p "$OUTPUT_DIR"

for command_name in curl python3 shasum; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $command_name"
    exit 1
  fi
done

json_value() {
  local file="$1"
  local expr="$2"
  python3 - "$file" "$expr" <<'PY'
import json
import sys
from pathlib import Path

file = Path(sys.argv[1])
expr = sys.argv[2]
data = json.loads(file.read_text())
value = data

for part in expr.split('.'):
    if not part:
        continue
    if part.endswith(']') and '[' in part:
        key, idx = part[:-1].split('[', 1)
        if key:
            if not isinstance(value, dict):
                value = None
                break
            value = value.get(key)
        if not isinstance(value, list):
            value = None
            break
        try:
            value = value[int(idx)]
        except (ValueError, IndexError):
            value = None
            break
    else:
        if not isinstance(value, dict):
            value = None
            break
        value = value.get(part)

print(value if value is not None else '')
PY
}

json_assert() {
  local json_file="$1"
  local expression="$2"
  local description="$3"

  if ! python3 - "$json_file" "$expression" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text())
expression = sys.argv[2]
safe_builtins = {"isinstance": isinstance, "len": len, "bool": bool, "int": int, "str": str, "list": list, "dict": dict}
result = eval(expression, {"__builtins__": safe_builtins}, {"data": payload})
sys.exit(0 if result else 1)
PY
  then
    echo "ASSERT FAIL: $description"
    cat "$json_file" || true
    exit 1
  fi

  echo "ASSERT OK: $description"
}

fail_with_body() {
  local message="$1"
  local body_file="$2"
  echo "ERROR: $message"
  cat "$body_file" || true
  exit 1
}

if [[ "$BOOTSTRAP_NODE" == "true" ]]; then
  echo "==> [0/10] Bootstrap local Node + test user"
  USE_H2="$USE_H2" TEST_USERNAME="$TEST_USERNAME" TEST_PASSWORD="$TEST_PASSWORD" NODE_PORT="$NODE_PORT" \
    "$ROOT_DIR/scripts/dev/start-client-test-node.sh" >/dev/null
fi

RUN_ID="$(python3 - <<'PY'
import uuid
print(uuid.uuid4())
PY
)"
REMOTE_PATH="/smoke/${RUN_ID}.txt"
REQUEST_ENTRY_ID="client-${RUN_ID}"
PAYLOAD_FILE="$TMP_DIR/payload.txt"
printf '%s run=%s at=%s\n' "$PAYLOAD_PREFIX" "$RUN_ID" "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" > "$PAYLOAD_FILE"
PAYLOAD_SIZE="$(wc -c < "$PAYLOAD_FILE" | tr -d ' ')"
PAYLOAD_CHECKSUM="$(shasum -a 256 "$PAYLOAD_FILE" | awk '{print $1}')"

echo "==> [1/10] Login"
LOGIN_CODE="$(curl -sS -o "$TMP_DIR/login.json" -w '%{http_code}' -X POST "$NODE_BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}")"

if [[ "$LOGIN_CODE" != "200" ]]; then
  fail_with_body "login failed with HTTP $LOGIN_CODE" "$TMP_DIR/login.json"
fi

json_assert "$TMP_DIR/login.json" "data.get('username') == '$TEST_USERNAME' and bool(data.get('token')) and isinstance(data.get('expiresAt'), str) and isinstance(data.get('quotaMb'), int)" "login response contract"
TOKEN="$(json_value "$TMP_DIR/login.json" token)"

if [[ -z "$TOKEN" ]]; then
  fail_with_body "login response missing token" "$TMP_DIR/login.json"
fi

echo "==> [2/10] Sync baseline (/sync/changes?since=0)"
SYNC_BASE_CODE="$(curl -sS -o "$TMP_DIR/sync-base.json" -w '%{http_code}' -X GET "$NODE_BASE_URL/sync/changes?since=0" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$SYNC_BASE_CODE" != "200" ]]; then
  fail_with_body "sync baseline failed with HTTP $SYNC_BASE_CODE" "$TMP_DIR/sync-base.json"
fi

json_assert "$TMP_DIR/sync-base.json" "data.get('username') == '$TEST_USERNAME' and isinstance(data.get('cursor'), int) and isinstance(data.get('snapshotAt'), str) and isinstance(data.get('changes'), list)" "sync baseline response contract"
BASE_CURSOR="$(json_value "$TMP_DIR/sync-base.json" cursor)"

if [[ ! "$BASE_CURSOR" =~ ^[0-9]+$ ]]; then
  fail_with_body "sync baseline returned invalid cursor" "$TMP_DIR/sync-base.json"
fi

echo "==> [3/10] Sync invalid cursor contract (since=-1)"
SYNC_INVALID_CODE="$(curl -sS -o "$TMP_DIR/sync-invalid.json" -w '%{http_code}' -X GET "$NODE_BASE_URL/sync/changes?since=-1" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$SYNC_INVALID_CODE" != "400" ]]; then
  fail_with_body "sync invalid cursor expected HTTP 400 but got $SYNC_INVALID_CODE" "$TMP_DIR/sync-invalid.json"
fi

json_assert "$TMP_DIR/sync-invalid.json" "data.get('errorCode') == 'SYNC_INVALID_SINCE' and isinstance(data.get('message'), str) and isinstance(data.get('timestamp'), str)" "sync invalid cursor error contract"

echo "==> [4/10] Upsert metadata (/fs/entries)"
cat > "$TMP_DIR/upsert-request.json" <<JSON
{
  "entryId": "$REQUEST_ENTRY_ID",
  "path": "$REMOTE_PATH",
  "entryType": "FILE",
  "sizeBytes": $PAYLOAD_SIZE,
  "checksum": "$PAYLOAD_CHECKSUM",
  "deleted": false
}
JSON

UPSERT_CODE="$(curl -sS -o "$TMP_DIR/upsert.json" -w '%{http_code}' -X POST "$NODE_BASE_URL/fs/entries" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data @"$TMP_DIR/upsert-request.json")"

if [[ "$UPSERT_CODE" != "200" ]]; then
  fail_with_body "upsert failed with HTTP $UPSERT_CODE" "$TMP_DIR/upsert.json"
fi

json_assert "$TMP_DIR/upsert.json" "data.get('path') == '$REMOTE_PATH' and data.get('entryType') == 'FILE' and data.get('sizeBytes') == $PAYLOAD_SIZE and str(data.get('checksum', '')).lower() == '$PAYLOAD_CHECKSUM' and isinstance(data.get('updatedAt'), str) and bool(data.get('entryId'))" "upsert response contract"
CANONICAL_ENTRY_ID="$(json_value "$TMP_DIR/upsert.json" entryId)"

if [[ -z "$CANONICAL_ENTRY_ID" ]]; then
  fail_with_body "upsert response missing entryId" "$TMP_DIR/upsert.json"
fi

echo "==> [5/10] Create upload session"
cat > "$TMP_DIR/upload-session-request.json" <<JSON
{
  "entryId": "$CANONICAL_ENTRY_ID"
}
JSON

SESSION_CODE="$(curl -sS -o "$TMP_DIR/upload-session.json" -w '%{http_code}' -X POST "$NODE_BASE_URL/files/upload-sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data @"$TMP_DIR/upload-session-request.json")"

if [[ "$SESSION_CODE" != "200" ]]; then
  fail_with_body "create upload session failed with HTTP $SESSION_CODE" "$TMP_DIR/upload-session.json"
fi

json_assert "$TMP_DIR/upload-session.json" "bool(data.get('sessionId')) and data.get('entryId') == '$CANONICAL_ENTRY_ID' and str(data.get('status')).upper() in ['OPEN', 'ACTIVE'] and isinstance(data.get('updatedAt'), str)" "upload session response contract"
SESSION_ID="$(json_value "$TMP_DIR/upload-session.json" sessionId)"

if [[ -z "$SESSION_ID" ]]; then
  fail_with_body "upload session response missing sessionId" "$TMP_DIR/upload-session.json"
fi

echo "==> [6/10] Upload chunks"
CHUNK_1_SIZE=$((PAYLOAD_SIZE / 2))
if (( CHUNK_1_SIZE < 1 )); then
  CHUNK_1_SIZE=1
fi

CHUNK_1_FILE="$TMP_DIR/chunk-1.bin"
CHUNK_2_FILE="$TMP_DIR/chunk-2.bin"

dd if="$PAYLOAD_FILE" of="$CHUNK_1_FILE" bs=1 count="$CHUNK_1_SIZE" status=none

dd if="$PAYLOAD_FILE" of="$CHUNK_2_FILE" bs=1 skip="$CHUNK_1_SIZE" status=none
CHUNK_2_SIZE="$(wc -c < "$CHUNK_2_FILE" | tr -d ' ')"

APPEND_1_CODE="$(curl -sS -o "$TMP_DIR/upload-append-1.json" -w '%{http_code}' -X PUT "$NODE_BASE_URL/files/upload-sessions/$SESSION_ID/chunks?offset=0" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @"$CHUNK_1_FILE")"

if [[ "$APPEND_1_CODE" != "200" ]]; then
  fail_with_body "append chunk #1 failed with HTTP $APPEND_1_CODE" "$TMP_DIR/upload-append-1.json"
fi

json_assert "$TMP_DIR/upload-append-1.json" "data.get('sessionId') == '$SESSION_ID' and data.get('entryId') == '$CANONICAL_ENTRY_ID' and data.get('uploadedBytes') == $CHUNK_1_SIZE" "append chunk #1 response contract"

if [[ "$CHUNK_2_SIZE" != "0" ]]; then
  APPEND_2_CODE="$(curl -sS -o "$TMP_DIR/upload-append-2.json" -w '%{http_code}' -X PUT "$NODE_BASE_URL/files/upload-sessions/$SESSION_ID/chunks?offset=$CHUNK_1_SIZE" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/octet-stream' \
    --data-binary @"$CHUNK_2_FILE")"

  if [[ "$APPEND_2_CODE" != "200" ]]; then
    fail_with_body "append chunk #2 failed with HTTP $APPEND_2_CODE" "$TMP_DIR/upload-append-2.json"
  fi

  json_assert "$TMP_DIR/upload-append-2.json" "data.get('sessionId') == '$SESSION_ID' and data.get('entryId') == '$CANONICAL_ENTRY_ID' and data.get('uploadedBytes') == $PAYLOAD_SIZE" "append chunk #2 response contract"
fi

echo "==> [7/10] Complete upload"
COMPLETE_CODE="$(curl -sS -o "$TMP_DIR/upload-complete.json" -w '%{http_code}' -X POST "$NODE_BASE_URL/files/upload-sessions/$SESSION_ID/complete" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$COMPLETE_CODE" != "200" ]]; then
  fail_with_body "complete upload failed with HTTP $COMPLETE_CODE" "$TMP_DIR/upload-complete.json"
fi

json_assert "$TMP_DIR/upload-complete.json" "data.get('entryId') == '$CANONICAL_ENTRY_ID' and data.get('sizeBytes') == $PAYLOAD_SIZE and str(data.get('checksum', '')).lower() == '$PAYLOAD_CHECKSUM'" "complete upload response contract"

echo "==> [8/10] Download and validate checksum/content"
DOWNLOAD_TARGET="$OUTPUT_DIR/$RUN_ID.txt"
DOWNLOAD_CODE="$(curl -sS -D "$TMP_DIR/download.headers" -o "$DOWNLOAD_TARGET" -w '%{http_code}' -X GET "$NODE_BASE_URL/files/entries/$CANONICAL_ENTRY_ID/content" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$DOWNLOAD_CODE" != "200" ]]; then
  fail_with_body "download failed with HTTP $DOWNLOAD_CODE" "$DOWNLOAD_TARGET"
fi

if ! cmp -s "$PAYLOAD_FILE" "$DOWNLOAD_TARGET"; then
  echo "ERROR: downloaded payload differs from uploaded payload"
  echo "Uploaded: $PAYLOAD_FILE"
  echo "Downloaded: $DOWNLOAD_TARGET"
  exit 1
fi

HEADER_CHECKSUM="$(awk -F': ' '{ if (tolower($1) == "x-content-sha256") print $2 }' "$TMP_DIR/download.headers" | tr -d '\r' | tail -n1 | tr '[:upper:]' '[:lower:]')"
if [[ -z "$HEADER_CHECKSUM" || "$HEADER_CHECKSUM" != "$PAYLOAD_CHECKSUM" ]]; then
  echo "ERROR: invalid X-Content-SHA256 header"
  cat "$TMP_DIR/download.headers" || true
  exit 1
fi

HEADER_CONTENT_TYPE="$(awk -F': ' '{ if (tolower($1) == "content-type") print tolower($2) }' "$TMP_DIR/download.headers" | tr -d '\r' | head -n1)"
if [[ "$HEADER_CONTENT_TYPE" != application/octet-stream* ]]; then
  echo "ERROR: unexpected content-type in download response: $HEADER_CONTENT_TYPE"
  cat "$TMP_DIR/download.headers" || true
  exit 1
fi

echo "==> [9/10] Sync delta after upload"
SYNC_DELTA_CODE="$(curl -sS -o "$TMP_DIR/sync-delta.json" -w '%{http_code}' -X GET "$NODE_BASE_URL/sync/changes?since=$BASE_CURSOR" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$SYNC_DELTA_CODE" != "200" ]]; then
  fail_with_body "sync delta failed with HTTP $SYNC_DELTA_CODE" "$TMP_DIR/sync-delta.json"
fi

json_assert "$TMP_DIR/sync-delta.json" "data.get('username') == '$TEST_USERNAME' and isinstance(data.get('cursor'), int) and data.get('cursor') >= int('$BASE_CURSOR') and isinstance(data.get('changes'), list)" "sync delta response contract"

if ! python3 - "$TMP_DIR/sync-delta.json" "$CANONICAL_ENTRY_ID" "$REMOTE_PATH" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text())
entry_id = sys.argv[2]
path = sys.argv[3]

for item in payload.get("changes", []):
    if item.get("entryId") == entry_id and item.get("path") == path and item.get("entryType") == "FILE":
        sys.exit(0)

sys.exit(1)
PY
then
  fail_with_body "sync delta does not include uploaded file change" "$TMP_DIR/sync-delta.json"
fi

echo "==> [10/10] Logout + revoked-session contract"
LOGOUT_CODE="$(curl -sS -o "$TMP_DIR/logout.json" -w '%{http_code}' -X POST "$NODE_BASE_URL/auth/logout" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$LOGOUT_CODE" != "204" ]]; then
  fail_with_body "logout failed with HTTP $LOGOUT_CODE" "$TMP_DIR/logout.json"
fi

POST_LOGOUT_TREE_CODE="$(curl -sS -o "$TMP_DIR/post-logout-tree.json" -w '%{http_code}' -X GET "$NODE_BASE_URL/fs/tree" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$POST_LOGOUT_TREE_CODE" != "401" ]]; then
  fail_with_body "expected HTTP 401 after logout, got $POST_LOGOUT_TREE_CODE" "$TMP_DIR/post-logout-tree.json"
fi

POST_LOGOUT_ME_CODE="$(curl -sS -o "$TMP_DIR/post-logout-me.json" -w '%{http_code}' -X GET "$NODE_BASE_URL/auth/me" \
  -H "Authorization: Bearer $TOKEN")"

if [[ "$POST_LOGOUT_ME_CODE" != "401" ]]; then
  fail_with_body "expected HTTP 401 in /auth/me after logout, got $POST_LOGOUT_ME_CODE" "$TMP_DIR/post-logout-me.json"
fi

json_assert "$TMP_DIR/post-logout-me.json" "data.get('errorCode') == 'INVALID_SESSION' and isinstance(data.get('message'), str) and isinstance(data.get('timestamp'), str)" "post-logout INVALID_SESSION contract"

echo
echo "SUCCESS: client smoke E2E flow passed"
echo "- Node base URL: $NODE_BASE_URL"
echo "- User: $TEST_USERNAME"
echo "- Remote path: $REMOTE_PATH"
echo "- Entry ID: $CANONICAL_ENTRY_ID"
echo "- Sync cursor baseline: $BASE_CURSOR"
echo "- Uploaded size: $PAYLOAD_SIZE"
echo "- SHA-256: $PAYLOAD_CHECKSUM"
echo "- Downloaded file: $DOWNLOAD_TARGET"
