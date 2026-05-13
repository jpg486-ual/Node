#!/usr/bin/env bash
# Smoke end-to-end signed: discovery query → negotiation create/confirm → recovery
# store/get con validación contra BD. Levanta el cluster docker, arranca/reusa claves
# ECDSA, ejecuta el flujo completo y comprueba estados persistidos.
# Flags: [--keep-running] [--no-build] [--reuse-keys] [--fast]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"
trap '/bin/rm -rf "$TMP_DIR"' EXIT

KEEP_RUNNING="false"
SKIP_BUILD="false"
REUSE_KEYS="false"

print_usage() {
  cat <<EOF
Usage: $0 [options]

Options:
  --keep-running  Keep docker stack running after the smoke completes.
  --no-build      Skip docker image build and run compose up -d.
  --reuse-keys    Reuse docker/keys and docker/env if present.
  --fast          Shortcut for: --no-build --reuse-keys --keep-running.
  --help          Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-running)
      KEEP_RUNNING="true"
      ;;
    --no-build)
      SKIP_BUILD="true"
      ;;
    --reuse-keys)
      REUSE_KEYS="true"
      ;;
    --fast)
      SKIP_BUILD="true"
      REUSE_KEYS="true"
      KEEP_RUNNING="true"
      ;;
    --help)
      print_usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown option '$1'"
      print_usage
      exit 1
      ;;
  esac
  shift
done

if ! command -v /usr/bin/python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required"
  exit 1
fi

DOCKER_BIN="$(command -v docker || true)"
if [[ -z "$DOCKER_BIN" ]]; then
  echo "ERROR: docker is required"
  exit 1
fi

if "$DOCKER_BIN" compose version >/dev/null 2>&1; then
  COMPOSE_BIN="$DOCKER_BIN compose"
  COMPOSE_IS_PLUGIN="true"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_BIN="$(command -v docker-compose)"
  COMPOSE_IS_PLUGIN="false"
else
  echo "ERROR: neither 'docker compose' nor 'docker-compose' is available"
  exit 1
fi

if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
  if command -v /opt/homebrew/bin/colima >/dev/null 2>&1; then
    echo "Docker daemon is down. Starting Colima..."
    /opt/homebrew/bin/colima start
  elif command -v /usr/local/bin/colima >/dev/null 2>&1; then
    echo "Docker daemon is down. Starting Colima..."
    /usr/local/bin/colima start
  else
    echo "ERROR: docker daemon is not available (and colima not found)."
    exit 1
  fi
fi

has_key_material() {
  [[ -f "$KEYS_DIR/node1-private.der" && -f "$KEYS_DIR/node1-public.der" &&
     -f "$KEYS_DIR/node2-private.der" && -f "$KEYS_DIR/node2-public.der" &&
     -f "$KEYS_DIR/node3-private.der" && -f "$KEYS_DIR/node3-public.der" &&
     -f "$DOCKER_DIR/env/node1.env" && -f "$DOCKER_DIR/env/node2.env" &&
  -f "$DOCKER_DIR/env/node3.env" ]] &&
  /usr/bin/grep -q '^NODE_TOPOLOGY_TUTOR_ACCEPTED_PUBLIC_KEYS=' "$DOCKER_DIR/env/node1.env" &&
  /usr/bin/grep -q '^NODE_TOPOLOGY_TUTOR_ACCEPTED_PUBLIC_KEYS=' "$DOCKER_DIR/env/node2.env" &&
  /usr/bin/grep -q '^NODE_TOPOLOGY_TUTOR_ACCEPTED_PUBLIC_KEYS=' "$DOCKER_DIR/env/node3.env"
}

if [[ "$REUSE_KEYS" == "true" ]] && has_key_material; then
  echo "==> Reusing existing node keys/env"
else
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
fi

if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster (no build)"
else
  echo "==> Building and starting cluster"
fi
cd "$ROOT_DIR"
if [[ "$COMPOSE_IS_PLUGIN" == "true" ]]; then
  if [[ "$SKIP_BUILD" == "true" ]]; then
    "$DOCKER_BIN" compose up -d
  else
    "$DOCKER_BIN" compose up --build -d
  fi
else
  if [[ "$SKIP_BUILD" == "true" ]]; then
    "$COMPOSE_BIN" up -d
  else
    "$COMPOSE_BIN" up --build -d
  fi
fi

REQUESTER_NODE1_ID="node-$(/usr/bin/shasum -a 256 "$KEYS_DIR/node1-public.der" | /usr/bin/awk '{print $1}' | /usr/bin/cut -c1-24)"
REQUESTER_NODE2_ID="node-$(/usr/bin/shasum -a 256 "$KEYS_DIR/node2-public.der" | /usr/bin/awk '{print $1}' | /usr/bin/cut -c1-24)"

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

  /usr/bin/printf "%s\n%s\n%s\n%s\n%s" "$method" "$canonical_path" "$canonical_query" "$nonce" "$timestamp" > "$canonical_file"
  /usr/bin/openssl pkey -inform DER -in "$priv_der" -out "$pem_file" >/dev/null 2>&1
  /usr/bin/openssl dgst -sha256 -sign "$pem_file" -out "$signature_file" "$canonical_file"
}

signed_call() {
  local node_index="$1"
  local method="$2"
  local base_url="$3"
  local request_path="$4"
  local payload_file="${5:-}"
  local out_body_file="$6"

  local pub_der="$KEYS_DIR/node${node_index}-public.der"
  local node_hash
  node_hash=$(/usr/bin/shasum -a 256 "$pub_der" | /usr/bin/awk '{print $1}')
  local node_id="node-${node_hash:0:24}"
  local nonce="nonce-$(/bin/date +%s)-$RANDOM"
  local timestamp
  timestamp="$(/bin/date +%s)"

  local signature_bin="$TMP_DIR/signature-${node_index}.bin"
  create_canonical_signature "$node_index" "$method" "$request_path" "$nonce" "$timestamp" "$signature_bin"
  local signature_base64
  signature_base64=$(/usr/bin/base64 < "$signature_bin" | /usr/bin/tr -d '\n')

  if [[ -n "$payload_file" ]]; then
    /usr/bin/curl -sS -o "$out_body_file" -w "%{http_code}" -X "$method" "$base_url$request_path" \
      -H "Content-Type: application/json" \
      -H "Accept: application/json" \
      -H "X-Node-Id: $node_id" \
      -H "X-Nonce: $nonce" \
      -H "X-Timestamp: $timestamp" \
      -H "X-Signature-Algorithm: SHA256withECDSA" \
      -H "X-Signature: $signature_base64" \
      --data @"$payload_file"
  else
    /usr/bin/curl -sS -o "$out_body_file" -w "%{http_code}" -X "$method" "$base_url$request_path" \
      -H "Accept: application/json" \
      -H "X-Node-Id: $node_id" \
      -H "X-Nonce: $nonce" \
      -H "X-Timestamp: $timestamp" \
      -H "X-Signature-Algorithm: SHA256withECDSA" \
      -H "X-Signature: $signature_base64"
  fi
}

wait_for_signed_discovery() {
  local attempts=30
  local payload_file="$TMP_DIR/discovery-wait.json"
  /bin/cat > "$payload_file" <<JSON
{
  "nodeId": "$REQUESTER_NODE1_ID",
  "failureDomain": "corp/a",
  "requestedBucket": 1024,
  "ratio": 1.0,
  "maxCandidates": 10,
  "targetFailureDomain": "corp/a"
}
JSON

  local body_file="$TMP_DIR/discovery-wait-body.json"
  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code=$(signed_call 1 "POST" "http://localhost:8082" "/ops/discovery/query" "$payload_file" "$body_file" || true)
    if [[ "$code" == "200" ]]; then
      return 0
    fi
    /bin/sleep 2
  done

  echo "ERROR: node2 discovery endpoint did not become ready in time"
  return 1
}

assert_json() {
  local expression="$1"
  local json_file="$2"
  local label="$3"
  if ! /usr/bin/python3 - "$expression" "$json_file" <<'PY'
import json
import sys

expr = sys.argv[1]
path = sys.argv[2]
with open(path, 'r', encoding='utf-8') as fh:
    data = json.load(fh)
safe_builtins = {'isinstance': isinstance, 'bool': bool, 'len': len, 'list': list}
ok = eval(expr, {'__builtins__': safe_builtins}, {'data': data})
sys.exit(0 if ok else 1)
PY
  then
    echo "ASSERT FAIL: $label"
    echo "Body: $(/bin/cat "$json_file")"
    return 1
  fi
  echo "ASSERT OK: $label"
}

echo "==> Waiting for signed discovery readiness"
wait_for_signed_discovery

echo "==> Step 1/5: signed discovery"
DISCOVERY_PAYLOAD="$TMP_DIR/discovery.json"
DISCOVERY_BODY="$TMP_DIR/discovery-body.json"
/bin/cat > "$DISCOVERY_PAYLOAD" <<JSON
{
  "nodeId": "$REQUESTER_NODE1_ID",
  "failureDomain": "corp/a",
  "requestedBucket": 1024,
  "ratio": 1.0,
  "maxCandidates": 10,
  "targetFailureDomain": "corp/a"
}
JSON

DISCOVERY_CODE=$(signed_call 1 "POST" "http://localhost:8082" "/ops/discovery/query" "$DISCOVERY_PAYLOAD" "$DISCOVERY_BODY")
[[ "$DISCOVERY_CODE" == "200" ]] || { echo "Discovery failed with HTTP $DISCOVERY_CODE"; exit 1; }
assert_json "'candidates' in data and isinstance(data['candidates'], list)" "$DISCOVERY_BODY" "discovery response contains candidates array"

echo "==> Step 2/3: signed negotiation create"
NEGOTIATE_CREATE_PAYLOAD="$TMP_DIR/negotiate-create.json"
NEGOTIATE_CREATE_BODY="$TMP_DIR/negotiate-create-body.json"
/bin/cat > "$NEGOTIATE_CREATE_PAYLOAD" <<JSON
{
  "requesterNodeId": "$REQUESTER_NODE1_ID",
  "targetNodeId": "$REQUESTER_NODE2_ID",
  "bucketSize": 1024,
  "expectedStorageBytes": 4096,
  "transferMode": "MANIFEST_ONLY",
  "fragmentCount": 4,
  "redundancyScheme": "RS(6,4)",
  "expirationSeconds": 120,
  "requesterSignature": "requester-signature",
  "fileManifest": {
    "fileId": "0d7f64c2-97cc-4400-a2a3-b3af056f85a1",
    "originalFileName": "dataset.bin",
    "originalSizeBytes": 100000,
    "compressedSizeBytes": 90000,
    "compressionAlgorithm": "zstd",
    "originalFileHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "fragmentCount": 4,
    "fragmentSize": 25000,
    "redundancyN": 6,
    "redundancyK": 4,
    "fragmentHashes": [
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    ]
  }
}
JSON

NEGOTIATE_CREATE_CODE=$(signed_call 1 "POST" "http://localhost:8082" "/negotiate" "$NEGOTIATE_CREATE_PAYLOAD" "$NEGOTIATE_CREATE_BODY")
[[ "$NEGOTIATE_CREATE_CODE" == "200" ]] || { echo "Negotiation create failed with HTTP $NEGOTIATE_CREATE_CODE"; exit 1; }
assert_json "bool(data.get('agreementId')) and data.get('status') == 'PENDING'" "$NEGOTIATE_CREATE_BODY" "negotiation create returns PENDING agreement"
AGREEMENT_ID=$(/usr/bin/python3 - "$NEGOTIATE_CREATE_BODY" <<'PY'
import json,sys
with open(sys.argv[1], 'r', encoding='utf-8') as fh:
    payload = json.load(fh)
print(payload['agreementId'])
PY
)

echo "==> Step 3/3: signed negotiation confirm"
NEGOTIATE_CONFIRM_PAYLOAD="$TMP_DIR/negotiate-confirm.json"
NEGOTIATE_CONFIRM_BODY="$TMP_DIR/negotiate-confirm-body.json"
/bin/cat > "$NEGOTIATE_CONFIRM_PAYLOAD" <<JSON
{
  "targetSignature": "target-signature"
}
JSON

NEGOTIATE_CONFIRM_CODE=$(signed_call 1 "POST" "http://localhost:8082" "/negotiate/${AGREEMENT_ID}/confirm" "$NEGOTIATE_CONFIRM_PAYLOAD" "$NEGOTIATE_CONFIRM_BODY")
[[ "$NEGOTIATE_CONFIRM_CODE" == "200" ]] || { echo "Negotiation confirm failed with HTTP $NEGOTIATE_CONFIRM_CODE"; exit 1; }
assert_json "data.get('agreementId') == '$AGREEMENT_ID' and data.get('status') == 'CONFIRMED'" "$NEGOTIATE_CONFIRM_BODY" "negotiation confirm returns CONFIRMED"

echo "==> Bonus: signed recovery tutor custody"
RECOVERY_FRAGMENT_ID="recovery-${AGREEMENT_ID}"
RECOVERY_REQUESTER_PUBLIC_KEY="$(/bin/cat "$KEYS_DIR/node1-public.b64")"
RECOVERY_STORE_PAYLOAD="$TMP_DIR/recovery-store.json"
RECOVERY_STORE_BODY="$TMP_DIR/recovery-store-body.json"
/bin/cat > "$RECOVERY_STORE_PAYLOAD" <<JSON
{
  "fragmentId": "$RECOVERY_FRAGMENT_ID",
  "agreementId": "$AGREEMENT_ID",
  "requesterNodeId": "$REQUESTER_NODE1_ID",
  "requesterPublicKey": "$RECOVERY_REQUESTER_PUBLIC_KEY",
  "checksumAlgorithm": "SHA-256",
  "checksum": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
  "payloadBase64": "aGVsbG8=",
  "custodySeconds": 120
}
JSON

RECOVERY_STORE_CODE=$(signed_call 1 "POST" "http://localhost:8082" "/recovery/fragments" "$RECOVERY_STORE_PAYLOAD" "$RECOVERY_STORE_BODY")
[[ "$RECOVERY_STORE_CODE" == "201" ]] || { echo "Recovery store failed with HTTP $RECOVERY_STORE_CODE"; echo "---response body---"; cat "$RECOVERY_STORE_BODY"; echo "---request payload---"; cat "$RECOVERY_STORE_PAYLOAD"; exit 1; }
assert_json "data.get('fragmentId') == '$RECOVERY_FRAGMENT_ID' and data.get('agreementId') == '$AGREEMENT_ID' and data.get('status') == 'STORED'" "$RECOVERY_STORE_BODY" "recovery store returns stored metadata"

RECOVERY_GET_BODY="$TMP_DIR/recovery-get-body.json"
RECOVERY_GET_CODE=$(signed_call 1 "GET" "http://localhost:8082" "/recovery/fragments/${RECOVERY_FRAGMENT_ID}" "" "$RECOVERY_GET_BODY")
[[ "$RECOVERY_GET_CODE" == "200" ]] || { echo "Recovery get failed with HTTP $RECOVERY_GET_CODE"; exit 1; }
assert_json "data.get('fragmentId') == '$RECOVERY_FRAGMENT_ID' and data.get('status') == 'STORED' and data.get('sizeBytes') == 5" "$RECOVERY_GET_BODY" "recovery get returns stored fragment metadata"

echo "==> Verifying DB persistence"
NEGOTIATION_STATUS=$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c "select status from negotiation_agreement where agreement_id = '$AGREEMENT_ID';" | /usr/bin/tr -d '\r')
[[ "$NEGOTIATION_STATUS" == "CONFIRMED" ]] || { echo "DB check failed: negotiation status is '$NEGOTIATION_STATUS'"; exit 1; }

NEGOTIATION_TOKEN=$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c "select transfer_token from negotiation_agreement where agreement_id = '$AGREEMENT_ID';" | /usr/bin/tr -d '\r')
[[ -n "$NEGOTIATION_TOKEN" ]] || { echo "DB check failed: transfer_token is empty"; exit 1; }

RECOVERY_TABLE_EXISTS=$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'recovery_orphan_fragment';" | /usr/bin/tr -d '\r')
if [[ "$RECOVERY_TABLE_EXISTS" != "1" ]]; then
  if [[ "$SKIP_BUILD" == "true" ]]; then
    echo "DB check failed: recovery_orphan_fragment table is missing in current runtime image."
    echo "Re-run without --no-build once so Flyway applies the new migration."
  else
    echo "DB check failed: recovery_orphan_fragment table is missing"
  fi
  exit 1
fi

RECOVERY_STORED=$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c "select fragment_id from recovery_orphan_fragment where fragment_id = '$RECOVERY_FRAGMENT_ID';" | /usr/bin/tr -d '\r')
[[ "$RECOVERY_STORED" == "$RECOVERY_FRAGMENT_ID" ]] || { echo "DB check failed: recovery fragment not stored"; exit 1; }

echo ""
echo "SMOKE E2E PASSED"
echo "- agreementId:   $AGREEMENT_ID"

if [[ "$KEEP_RUNNING" == "true" ]]; then
  echo "Cluster remains running (--keep-running)."
else
  echo "Stopping cluster (use --keep-running to skip)."
  if [[ "$COMPOSE_IS_PLUGIN" == "true" ]]; then
    "$DOCKER_BIN" compose down -v
  else
    "$COMPOSE_BIN" down -v
  fi
fi
