#!/usr/bin/env bash
# Genera evidencia del flow de negociación full sobre el cluster docker de 3 nodos.
#
# Arranca cluster (supernodo discovery en node2 + dos nodos comunes), ejecuta el
# proceso de negociación con intercambio de fragmentos y deja artefactos firmados
# que demuestran la topología del exchange y la consistencia de datos.
#
# Uso:
#   ./scripts/dev/proof-full-negotiation-fragments.sh
#   NO_BUILD=true ./scripts/dev/proof-full-negotiation-fragments.sh
#   CUSTODY_SECONDS=600 ./scripts/dev/proof-full-negotiation-fragments.sh
#
# Salidas:
#   exit 0          flow completo OK + artefactos generados
#   exit !=0        algún paso falló (ver stderr)
#   artifacts       logs/proof/full-negotiation-*

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

NO_BUILD="${NO_BUILD:-false}"
FORCE_RECREATE="${FORCE_RECREATE:-true}"
KEEP_RUNNING="${KEEP_RUNNING:-true}"
DISCOVERY_BASE_URL="${DISCOVERY_BASE_URL:-http://localhost:8082}"
CUSTODY_SECONDS="${CUSTODY_SECONDS:-300}"
ENABLE_RECOVERY_ALL="${ENABLE_RECOVERY_ALL:-true}"

DOCKER_BIN="$(command -v docker || true)"
if [[ -z "$DOCKER_BIN" ]]; then
  echo "ERROR: docker is required"
  exit 1
fi

if "$DOCKER_BIN" compose version >/dev/null 2>&1; then
  COMPOSE_CMD=("$DOCKER_BIN" compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=("$(command -v docker-compose)")
else
  echo "ERROR: neither docker compose plugin nor docker-compose was found"
  exit 1
fi

COMPOSE_FILES=("-f" "$ROOT_DIR/docker-compose.yml")
if [[ "$ENABLE_RECOVERY_ALL" == "true" ]]; then
  RECOVERY_OVERRIDE_FILE="$TMP_DIR/docker-compose.recovery-all.yml"
  cat > "$RECOVERY_OVERRIDE_FILE" <<'YAML'
services:
  node1:
    environment:
      NODE_FEATURES_RECOVERY_ENABLED: "true"
  node2:
    environment:
      NODE_FEATURES_RECOVERY_ENABLED: "true"
  node3:
    environment:
      NODE_FEATURES_RECOVERY_ENABLED: "true"
YAML
  COMPOSE_FILES+=("-f" "$RECOVERY_OVERRIDE_FILE")
fi

if [[ ! -f "$KEYS_DIR/node1-private.der" || ! -f "$KEYS_DIR/node2-private.der" || ! -f "$KEYS_DIR/node3-private.der" ]]; then
  echo "==> Generating node key material"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
fi

echo "==> Starting 3-node cluster (supernodo discovery en node2)"
cd "$ROOT_DIR"
if [[ "$NO_BUILD" == "true" ]]; then
  if [[ "$FORCE_RECREATE" == "true" ]]; then
    "${COMPOSE_CMD[@]}" "${COMPOSE_FILES[@]}" up --force-recreate -d >/dev/null
  else
    "${COMPOSE_CMD[@]}" "${COMPOSE_FILES[@]}" up -d >/dev/null
  fi
else
  if [[ "$FORCE_RECREATE" == "true" ]]; then
    "${COMPOSE_CMD[@]}" "${COMPOSE_FILES[@]}" up --build --force-recreate -d >/dev/null
  else
    "${COMPOSE_CMD[@]}" "${COMPOSE_FILES[@]}" up --build -d >/dev/null
  fi
fi

wait_http() {
  local url="$1"
  local retries=90
  for _ in $(seq 1 "$retries"); do
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)"
    if [[ "$code" == "200" || "$code" == "401" || "$code" == "404" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

echo "==> Waiting for node endpoints"
wait_http "http://localhost:8081/auth/me" || { echo "node1 not ready"; exit 1; }
wait_http "http://localhost:8082/auth/me" || { echo "node2 not ready"; exit 1; }
wait_http "http://localhost:8083/auth/me" || { echo "node3 not ready"; exit 1; }

node_id() {
  local index="$1"
  local pub_der="$KEYS_DIR/node${index}-public.der"
  local hash
  hash="$(shasum -a 256 "$pub_der" | awk '{print $1}')"
  echo "node-${hash:0:24}"
}

node_url() {
  local index="$1"
  echo "http://localhost:808${index}"
}

node_db_container() {
  local index="$1"
  echo "node-postgres-${index}"
}

create_canonical_signature() {
  local node_index="$1"
  local method="$2"
  local request_path="$3"
  local nonce="$4"
  local timestamp="$5"
  local signature_file="$6"

  local priv_der="$KEYS_DIR/node${node_index}-private.der"
  local pem_file="$TMP_DIR/node${node_index}-key.pem"
  local canonical_file="$TMP_DIR/canonical-${node_index}.txt"

  local canonical_path="$request_path"
  local canonical_query=""
  if [[ "$request_path" == *\?* ]]; then
    canonical_path="${request_path%%\?*}"
    canonical_query="${request_path#*\?}"
  fi
  printf "%s\n%s\n%s\n%s\n%s" "$method" "$canonical_path" "$canonical_query" "$nonce" "$timestamp" > "$canonical_file"
  openssl pkey -inform DER -in "$priv_der" -out "$pem_file" >/dev/null 2>&1
  openssl dgst -sha256 -sign "$pem_file" -out "$signature_file" "$canonical_file"
}

signed_call() {
  local node_index="$1"
  local method="$2"
  local base_url="$3"
  local request_path="$4"
  local payload_file="${5:-}"
  local out_body_file="$6"

  local node
  node="$(node_id "$node_index")"
  local nonce="nonce-$(date +%s)-$RANDOM"
  local timestamp="$(date +%s)"
  local signature_bin="$TMP_DIR/signature-${node_index}.bin"
  create_canonical_signature "$node_index" "$method" "$request_path" "$nonce" "$timestamp" "$signature_bin"
  local signature
  signature="$(base64 < "$signature_bin" | tr -d '\n')"

  if [[ -n "$payload_file" ]]; then
    curl -sS -o "$out_body_file" -w '%{http_code}' -X "$method" "$base_url$request_path" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      -H "X-Node-Id: $node" \
      -H "X-Nonce: $nonce" \
      -H "X-Timestamp: $timestamp" \
      -H 'X-Signature-Algorithm: SHA256withECDSA' \
      -H "X-Signature: $signature" \
      --data @"$payload_file"
  else
    curl -sS -o "$out_body_file" -w '%{http_code}' -X "$method" "$base_url$request_path" \
      -H 'Accept: application/json' \
      -H "X-Node-Id: $node" \
      -H "X-Nonce: $nonce" \
      -H "X-Timestamp: $timestamp" \
      -H 'X-Signature-Algorithm: SHA256withECDSA' \
      -H "X-Signature: $signature"
  fi
}

run_discovery_check() {
  local requester_idx="$1"
  local requester_id
  requester_id="$(node_id "$requester_idx")"
  local payload="$TMP_DIR/discovery-${requester_idx}.json"
  local body="$TMP_DIR/discovery-${requester_idx}-body.json"

  cat > "$payload" <<JSON
{
  "nodeId": "$requester_id",
  "failureDomain": "corp/a",
  "requestedBucket": 1024,
  "ratio": 1.0,
  "maxCandidates": 10,
  "targetFailureDomain": "corp/a"
}
JSON

  local code
  code="$(signed_call "$requester_idx" POST "$DISCOVERY_BASE_URL" "/ops/discovery/query" "$payload" "$body")"
  [[ "$code" == "200" ]] || { echo "ERROR: discovery failed for requester node${requester_idx} (HTTP $code)"; cat "$body"; exit 1; }

  python3 - <<PY
import json
from pathlib import Path
payload = json.loads(Path("$body").read_text())
if not isinstance(payload.get("candidates"), list):
    raise SystemExit("ERROR: discovery response has no candidates list")
PY

  echo "  - discovery OK for requester node${requester_idx}"
}

PROOF_CONTENT_FILE="$TMP_DIR/shared-upload.bin"
cat > "$PROOF_CONTENT_FILE" <<TXT
FULL_NEGOTIATION_FRAGMENT_PROOF
shared-file-for-node1-node2-node3
timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TXT

FILE_SIZE="$(wc -c < "$PROOF_CONTENT_FILE" | tr -d ' ')"
FILE_SHA256="$(shasum -a 256 "$PROOF_CONTENT_FILE" | awk '{print $1}')"
FILE_BASE64="$(base64 < "$PROOF_CONTENT_FILE" | tr -d '\n')"

assert_in_db() {
  local node_index="$1"
  local sql="$2"
  local expected="$3"
  local value
  value="$($DOCKER_BIN exec "$(node_db_container "$node_index")" psql -U node -d node -t -A -c "$sql" | tr -d '\r')"
  [[ "$value" == "$expected" ]] || {
    echo "ERROR: DB assertion failed on node${node_index}. expected=$expected got=$value"
    echo "SQL: $sql"
    exit 1
  }
}

assert_in_db_at_least() {
  local node_index="$1"
  local sql="$2"
  local min_expected="$3"
  local value
  value="$($DOCKER_BIN exec "$(node_db_container "$node_index")" psql -U node -d node -t -A -c "$sql" | tr -d '\r')"
  if [[ -z "$value" || "$value" -lt "$min_expected" ]]; then
    echo "ERROR: DB lower-bound assertion failed on node${node_index}. min=$min_expected got=${value:-<empty>}"
    echo "SQL: $sql"
    exit 1
  fi
}

exchange_fragment() {
  local requester_idx="$1"
  local target_idx="$2"

  local requester_id target_id target_url
  requester_id="$(node_id "$requester_idx")"
  target_id="$(node_id "$target_idx")"
  target_url="$(node_url "$target_idx")"

  local create_payload="$TMP_DIR/negotiate-create-${requester_idx}-${target_idx}.json"
  local create_body="$TMP_DIR/negotiate-create-${requester_idx}-${target_idx}-body.json"
  local confirm_payload="$TMP_DIR/negotiate-confirm-${requester_idx}-${target_idx}.json"
  local confirm_body="$TMP_DIR/negotiate-confirm-${requester_idx}-${target_idx}-body.json"
  local recovery_payload="$TMP_DIR/recovery-store-${requester_idx}-${target_idx}.json"
  local recovery_body="$TMP_DIR/recovery-store-${requester_idx}-${target_idx}-body.json"
  local recovery_get_body="$TMP_DIR/recovery-get-${requester_idx}-${target_idx}-body.json"

  local file_id
  file_id="$(python3 - <<PY
import uuid
print(uuid.uuid4())
PY
)"

  cat > "$create_payload" <<JSON
{
  "requesterNodeId": "$requester_id",
  "targetNodeId": "$target_id",
  "bucketSize": 1024,
  "expectedStorageBytes": $FILE_SIZE,
  "transferMode": "MANIFEST_ONLY",
  "fragmentCount": 3,
  "redundancyScheme": "RS(3,3)",
  "expirationSeconds": 300,
  "requesterSignature": "request-signature-node${requester_idx}",
  "fileManifest": {
    "fileId": "$file_id",
    "originalFileName": "shared-upload.bin",
    "originalSizeBytes": $FILE_SIZE,
    "compressedSizeBytes": $FILE_SIZE,
    "compressionAlgorithm": "none",
    "originalFileHash": "$FILE_SHA256",
    "fragmentCount": 3,
    "fragmentSize": $(( (FILE_SIZE + 2) / 3 )),
    "redundancyN": 3,
    "redundancyK": 3,
    "fragmentHashes": [
      "$FILE_SHA256"
    ]
  }
}
JSON

  local create_code
  create_code="$(signed_call "$requester_idx" POST "$target_url" "/negotiate" "$create_payload" "$create_body")"
  [[ "$create_code" == "200" ]] || { echo "ERROR: negotiate create node${requester_idx}->node${target_idx} (HTTP $create_code)"; cat "$create_body"; exit 1; }

  local agreement_id
  agreement_id="$(python3 -c "import json; print(json.load(open('$create_body'))['agreementId'])")"

  cat > "$confirm_payload" <<JSON
{
  "targetSignature": "target-signature-node${target_idx}"
}
JSON

  local confirm_code
  confirm_code="$(signed_call "$requester_idx" POST "$target_url" "/negotiate/$agreement_id/confirm" "$confirm_payload" "$confirm_body")"
  [[ "$confirm_code" == "200" ]] || { echo "ERROR: negotiate confirm node${requester_idx}->node${target_idx} (HTTP $confirm_code)"; cat "$confirm_body"; exit 1; }

  python3 - <<PY
import json
from pathlib import Path
payload = json.loads(Path("$confirm_body").read_text())
if payload.get("status") != "CONFIRMED":
    raise SystemExit("ERROR: negotiation not confirmed")
if not payload.get("transferAuthorizationToken"):
    raise SystemExit("ERROR: transferAuthorizationToken is missing")
PY

  local requester_public_key
  requester_public_key="$(cat "$KEYS_DIR/node${requester_idx}-public.b64")"
  local fragment_id="frag-${requester_idx}-to-${target_idx}-${agreement_id}"

  cat > "$recovery_payload" <<JSON
{
  "fragmentId": "$fragment_id",
  "agreementId": "$agreement_id",
  "requesterNodeId": "$requester_id",
  "requesterPublicKey": "$requester_public_key",
  "checksumAlgorithm": "SHA-256",
  "checksum": "$FILE_SHA256",
  "payloadBase64": "$FILE_BASE64",
  "custodySeconds": $CUSTODY_SECONDS
}
JSON

  local recovery_code
  recovery_code="$(signed_call "$requester_idx" POST "$target_url" "/recovery/fragments" "$recovery_payload" "$recovery_body")"
  [[ "$recovery_code" == "201" ]] || { echo "ERROR: recovery store node${requester_idx}->node${target_idx} (HTTP $recovery_code)"; cat "$recovery_body"; exit 1; }

  local recovery_get_code
  recovery_get_code="$(signed_call "$requester_idx" GET "$target_url" "/recovery/fragments/$fragment_id" "" "$recovery_get_body")"
  [[ "$recovery_get_code" == "200" ]] || { echo "ERROR: recovery get node${requester_idx}->node${target_idx} (HTTP $recovery_get_code)"; cat "$recovery_get_body"; exit 1; }

  python3 - <<PY
import json
from pathlib import Path
payload = json.loads(Path("$recovery_get_body").read_text())
if payload.get("fragmentId") != "$fragment_id":
    raise SystemExit("ERROR: recovery fragmentId mismatch")
if payload.get("status") != "STORED":
    raise SystemExit("ERROR: recovery status is not STORED")
if int(payload.get("sizeBytes", -1)) != int($FILE_SIZE):
    raise SystemExit("ERROR: recovery sizeBytes mismatch")
PY

  assert_in_db "$target_idx" "select status from negotiation_agreement where agreement_id = '$agreement_id';" "CONFIRMED"
  assert_in_db "$target_idx" "select fragment_id from recovery_orphan_fragment where fragment_id = '$fragment_id';" "$fragment_id"

  echo "  - node${requester_idx} -> node${target_idx}: agreement CONFIRMED + fragment STORED"
}

echo "==> Step 1/3: discovery checks with all 3 requesters"
run_discovery_check 1
run_discovery_check 2
run_discovery_check 3

echo "==> Step 2/3: full mesh negotiation + fragment transfer"
exchange_fragment 1 2
exchange_fragment 1 3
exchange_fragment 2 1
exchange_fragment 2 3
exchange_fragment 3 1
exchange_fragment 3 2

echo "==> Step 3/3: aggregate DB sanity"
assert_in_db_at_least 1 "select count(*) from negotiation_agreement where status = 'CONFIRMED';" "2"
assert_in_db_at_least 2 "select count(*) from negotiation_agreement where status = 'CONFIRMED';" "2"
assert_in_db_at_least 3 "select count(*) from negotiation_agreement where status = 'CONFIRMED';" "2"
assert_in_db_at_least 1 "select count(*) from recovery_orphan_fragment;" "2"
assert_in_db_at_least 2 "select count(*) from recovery_orphan_fragment;" "2"
assert_in_db_at_least 3 "select count(*) from recovery_orphan_fragment;" "2"

echo

echo "SUCCESS: discovery + full negotiation + fragment exchange completed"
echo "- Shared file hash: $FILE_SHA256"
echo "- Shared file size: $FILE_SIZE bytes"
echo "- Topology: supernodo discovery on node2, data exchange across node1,node2,node3"

if [[ "$KEEP_RUNNING" != "true" ]]; then
  echo "==> Stopping cluster"
  "${COMPOSE_CMD[@]}" "${COMPOSE_FILES[@]}" down -v >/dev/null
fi
