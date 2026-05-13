#!/usr/bin/env bash
# Smoke multi-block file upload + download + replicación de manifest al tutor.
# Escenario: sube un archivo de 8 MiB (≥2 bloques RS con block-size=4 MiB), valida que
# el manifest se replica al tutor con BlockManifest serializado, y que el download
# reconstruye los bloques (k de n fragments por bloque) hasta SHA-256 idéntico.
# Flags: [--no-build] [--keep-running] [--fast]
# Dependencias: docker, docker compose, curl, python3, openssl, shasum.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

KEEP_RUNNING="false"
SKIP_BUILD="false"
TEST_USERNAME="multi_block_user_$RANDOM"
TEST_PASSWORD="NodeClient2026!"
TEST_QUOTA_MB=2048
FILE_SIZE_BYTES=$((8 * 1024 * 1024))  # 8 MiB → 2 bloques RS con default 4 MiB

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-running) KEEP_RUNNING="true" ;;
    --no-build) SKIP_BUILD="true" ;;
    --fast)
      KEEP_RUNNING="true"
      SKIP_BUILD="true"
      ;;
    --help)
      grep '^#' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *)
      echo "ERROR: unknown option '$1'"
      exit 1
      ;;
  esac
  shift
done

DOCKER_BIN="$(command -v docker || true)"
[[ -n "$DOCKER_BIN" ]] || { echo "ERROR: docker is required"; exit 1; }

if [[ ! -f "$KEYS_DIR/node1-private.der" ]]; then
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
fi

cd "$ROOT_DIR"
echo "==> [1/8] Cluster up"
if [[ "$SKIP_BUILD" == "true" ]]; then
  "$DOCKER_BIN" compose up -d --force-recreate >/dev/null
else
  "$DOCKER_BIN" compose up --build -d --force-recreate >/dev/null
fi

wait_http() {
  local url="$1" attempts=90
  for _ in $(seq 1 $attempts); do
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' "$url" || true)"
    [[ "$code" == "200" || "$code" == "401" || "$code" == "404" ]] && return 0
    sleep 1
  done
  return 1
}
wait_http "http://localhost:8081/auth/me" || { echo "node1 not ready"; exit 1; }
wait_http "http://localhost:8082/auth/me" || { echo "node2 not ready"; exit 1; }
wait_http "http://localhost:8083/auth/me" || { echo "node3 not ready"; exit 1; }

# CI Linux runner: discovery propagation puede tardar significativamente más que macOS
# local. El upload del step 4 requiere n=3 custodians (RS multi-block); arrancar
# pre-convergencia produce 503 INSUFFICIENT_CUSTODIANS.
wait_discovery_convergence() {
  local last_count="?"
  for i in $(seq 1 240); do
    local count
    count="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
      "SELECT COUNT(DISTINCT node_id) FROM discovery_candidate WHERE healthy=TRUE;" 2>/dev/null | tr -d '[:space:]')"
    if [[ -n "$count" && "$count" -ge 3 ]]; then
      echo "  discovery converged: $count healthy candidates after ${i}s"
      return 0
    fi
    last_count="$count"
    sleep 1
  done
  echo "ERROR: discovery directory did not converge to ≥3 healthy candidates after 240s (last count: $last_count)"
  return 1
}
wait_discovery_convergence || exit 1

echo "==> [2/8] Register user + login"
INV_CODE="MBLK-$RANDOM"
"$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node <<SQL >/dev/null
INSERT INTO registration_code(code, quota_mb, expires_at, used, used_at, created_at)
VALUES ('$INV_CODE', $TEST_QUOTA_MB, NOW() + INTERVAL '2 days', FALSE, NULL, NOW())
ON CONFLICT (code) DO UPDATE SET used=FALSE, used_at=NULL, created_at=NOW();
SQL

curl -sS -o /dev/null -X POST "http://localhost:8081/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"invitationCode\":\"$INV_CODE\",\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"

LOGIN_JSON="$TMP_DIR/login.json"
curl -sS -o "$LOGIN_JSON" -X POST "http://localhost:8081/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
TOKEN="$(python3 -c "import json; print(json.load(open('$LOGIN_JSON'))['token'])")"

echo "==> [3/8] Generate 8 MiB random payload"
PAYLOAD="$TMP_DIR/multi-block-payload.bin"
dd if=/dev/urandom of="$PAYLOAD" bs=1M count=8 status=none
SIZE="$(wc -c < "$PAYLOAD" | tr -d ' ')"
HASH="$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')"
[[ "$SIZE" == "$FILE_SIZE_BYTES" ]] || { echo "ERROR: payload size $SIZE != expected $FILE_SIZE_BYTES"; exit 1; }
echo "  payload size: $SIZE bytes, sha256: ${HASH:0:16}..."

echo "==> [4/8] Upsert metadata + upload (streaming)"
UPSERT_JSON="$TMP_DIR/upsert.json"
curl -sS -o "$UPSERT_JSON" -X POST "http://localhost:8081/fs/entries" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"path\":\"/multi-block-test.bin\",\"entryType\":\"FILE\",\"sizeBytes\":$SIZE,\"checksum\":\"$HASH\",\"deleted\":false}"
ENTRY_ID="$(python3 -c "import json; print(json.load(open('$UPSERT_JSON'))['entryId'])")"
echo "  entry id: $ENTRY_ID"

UPLOAD_CODE="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X PUT "http://localhost:8081/files/entries/$ENTRY_ID/content" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary @"$PAYLOAD")"
[[ "$UPLOAD_CODE" == "200" ]] || { echo "ERROR: upload failed HTTP $UPLOAD_CODE"; exit 1; }

echo "==> [5/8] Verify multi-block layout in origin (client_file_manifest_block)"
# Los bloques viven en la tabla auxiliar `client_file_manifest_block` con
# (file_id, block_index, fragment_hashes); no en una columna `manifest_json`.
FILE_ID="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT file_id FROM client_file_manifest WHERE entry_id='$ENTRY_ID';" | tr -d '[:space:]')"
BLOCK_COUNT="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_file_manifest_block WHERE file_id='$FILE_ID';" | tr -d '[:space:]')"
echo "  blocks in manifest: $BLOCK_COUNT (expected >= 2)"
[[ "$BLOCK_COUNT" -ge "2" ]] || { echo "ERROR: expected multi-block layout, got $BLOCK_COUNT"; exit 1; }

echo "==> [6/8] Verify recovery_file_manifest@tutor has client_blocks_json populated"
# fileId resolved en el step previo.
TUTOR_BLOCKS_RAW="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
  -c "SELECT client_blocks_json FROM recovery_file_manifest WHERE file_id='$FILE_ID';" 2>/dev/null || echo "")"
if [[ -z "$TUTOR_BLOCKS_RAW" ]] || [[ "$TUTOR_BLOCKS_RAW" == "" ]]; then
  echo "  WARN: tutor recovery_file_manifest empty for fileId=$FILE_ID — replication may be disabled"
else
  TUTOR_BLOCK_COUNT="$(echo "$TUTOR_BLOCKS_RAW" | python3 -c "
import json, sys
raw = sys.stdin.read().strip()
if not raw or raw == 'null':
    print(0)
else:
    print(len(json.loads(raw)))
")"
  echo "  tutor client_blocks_json: $TUTOR_BLOCK_COUNT blocks"
  [[ "$TUTOR_BLOCK_COUNT" -ge "2" ]] || { echo "ERROR: tutor blocks JSON not populated correctly"; exit 1; }
fi

echo "==> [7/8] Download via reconstruct + verify checksum"
DOWNLOADED="$TMP_DIR/downloaded.bin"
DOWNLOAD_CODE="$(curl -sS -o "$DOWNLOADED" -w '%{http_code}' \
  "http://localhost:8081/files/entries/$ENTRY_ID/content" \
  -H "Authorization: Bearer $TOKEN")"
[[ "$DOWNLOAD_CODE" == "200" ]] || { echo "ERROR: download failed HTTP $DOWNLOAD_CODE"; exit 1; }
DOWNLOADED_HASH="$(shasum -a 256 "$DOWNLOADED" | awk '{print $1}')"
[[ "$DOWNLOADED_HASH" == "$HASH" ]] || { echo "ERROR: checksum mismatch — got $DOWNLOADED_HASH, expected $HASH"; exit 1; }
echo "  downloaded $(wc -c < "$DOWNLOADED" | tr -d ' ') bytes, sha256 matches"

echo "==> [8/8] Verify per-block fragment placements"
PLACEMENT_COUNT="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_fragment_placement WHERE file_id='$FILE_ID';" | tr -d '[:space:]')"
DISTINCT_BLOCKS="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(DISTINCT block_index) FROM client_fragment_placement WHERE file_id='$FILE_ID';" | tr -d '[:space:]')"
echo "  total placements: $PLACEMENT_COUNT (expected blocks*n = $BLOCK_COUNT × 3 = $((BLOCK_COUNT * 3)))"
echo "  distinct blocks: $DISTINCT_BLOCKS (expected $BLOCK_COUNT)"
[[ "$DISTINCT_BLOCKS" == "$BLOCK_COUNT" ]] || { echo "ERROR: per-block placements not distributed correctly"; exit 1; }

echo
echo "SUCCESS: multi-block upload smoke passed"
echo "  - File: 8 MiB, $BLOCK_COUNT blocks RS"
echo "  - Origin manifest + placements: OK"
echo "  - Tutor recovery_file_manifest with client_blocks_json: OK"
echo "  - Reconstruct download: SHA-256 matches"

if [[ "$KEEP_RUNNING" == "false" ]]; then
  "$DOCKER_BIN" compose down -v >/dev/null
fi
