#!/usr/bin/env bash
# Smoke regression de descarga de bytes desde recovery: POST /recovery/fragments
# (octet-stream) + GET /recovery/fragments/{id}/content y verificación de checksum.
# Flags: [--keep-running] [--no-build] [--reuse-keys] [--fast]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"
trap '/bin/rm -rf "$TMP_DIR"' EXIT

KEEP_RUNNING="false"
SKIP_BUILD="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-running) KEEP_RUNNING="true" ;;
    --no-build) SKIP_BUILD="true" ;;
    --reuse-keys) ;;
    --fast)
      KEEP_RUNNING="true"
      SKIP_BUILD="true"
      ;;
    --help)
      cat <<EOF
Usage: $0 [--keep-running] [--no-build] [--reuse-keys] [--fast]

Runs a real Docker smoke that validates recovery byte custody and content download.
EOF
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
if [[ -z "$DOCKER_BIN" ]]; then
  echo "ERROR: docker is required"
  exit 1
fi

if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
  echo "ERROR: docker daemon is not available"
  exit 1
fi

has_key_material() {
  [[ -f "$KEYS_DIR/node1-private.der" && -f "$KEYS_DIR/node1-public.der" &&
     -f "$KEYS_DIR/node2-private.der" && -f "$KEYS_DIR/node2-public.der" &&
     -f "$KEYS_DIR/node3-private.der" && -f "$KEYS_DIR/node3-public.der" &&
     -f "$DOCKER_DIR/env/node1.env" && -f "$DOCKER_DIR/env/node2.env" &&
     -f "$DOCKER_DIR/env/node3.env" ]]
}

if has_key_material; then
  echo "==> Reusing existing node keys/env"
else
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
fi

REQUESTER_PUBLIC_KEY=$(/usr/bin/tr -d '\n' < "$KEYS_DIR/node1-public.b64")

cd "$ROOT_DIR"
if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster (no build)"
  "$DOCKER_BIN" compose up -d
else
  echo "==> Building and starting cluster"
  "$DOCKER_BIN" compose up --build -d
fi

create_signature() {
  local node_index="$1"
  local method="$2"
  local request_path="$3"
  local nonce="$4"
  local timestamp="$5"
  local signature_file="$6"

  local priv_der="$KEYS_DIR/node${node_index}-private.der"
  local key_pem_file="$TMP_DIR/node${node_index}-private.pem"
  local canonical_file="$TMP_DIR/node${node_index}-${RANDOM}.canonical"
  local canonical_path="$request_path"
  local canonical_query=""

  if [[ "$request_path" == *\?* ]]; then
    canonical_path="${request_path%%\?*}"
    canonical_query="${request_path#*\?}"
  fi

  /usr/bin/printf "%s\n%s\n%s\n%s\n%s" "$method" "$canonical_path" "$canonical_query" "$nonce" "$timestamp" > "$canonical_file"
  /usr/bin/openssl pkey -inform DER -in "$priv_der" -out "$key_pem_file" >/dev/null 2>&1
  /usr/bin/openssl dgst -sha256 -sign "$key_pem_file" -out "$signature_file" "$canonical_file"
}

signed_json_call() {
  local node_index="$1"
  local method="$2"
  local base_url="$3"
  local request_path="$4"
  local payload_file="$5"
  local body_out="$6"

  local raw_response
  raw_response=$(
    "$DOCKER_DIR/scripts/signed-request.sh" "$node_index" "$method" "$base_url" "$request_path" "$payload_file" || true
  )

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

signed_binary_get() {
  local node_index="$1"
  local base_url="$2"
  local request_path="$3"
  local body_out="$4"
  local headers_out="$5"

  local pub_der="$KEYS_DIR/node${node_index}-public.der"
  local node_hash
  node_hash=$(/usr/bin/shasum -a 256 "$pub_der" | /usr/bin/awk '{print $1}')
  local node_id="node-${node_hash:0:24}"
  local nonce="nonce-$(/bin/date +%s)-$RANDOM"
  local timestamp
  timestamp="$(/bin/date +%s)"

  local signature_bin="$TMP_DIR/signature-${node_index}-${RANDOM}.bin"
  create_signature "$node_index" "GET" "$request_path" "$nonce" "$timestamp" "$signature_bin"
  local signature_base64
  signature_base64=$(/usr/bin/base64 < "$signature_bin" | /usr/bin/tr -d '\n')

  /usr/bin/curl -sS -D "$headers_out" -o "$body_out" -w "%{http_code}" -X GET "$base_url$request_path" \
    -H "Accept: application/octet-stream" \
    -H "X-Node-Id: $node_id" \
    -H "X-Nonce: $nonce" \
    -H "X-Timestamp: $timestamp" \
    -H "X-Signature-Algorithm: SHA256withECDSA" \
    -H "X-Signature: $signature_base64"
}

sha256_hex() {
  local value="$1"
  /usr/bin/printf "%s" "$value" | /usr/bin/shasum -a 256 | /usr/bin/awk '{print $1}'
}

wait_for_node_ready() {
  local attempts=40
  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code=$(/usr/bin/curl -sS -o /dev/null -w "%{http_code}" "http://localhost:8082/actuator/health" || true)
    code=$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')
    if [[ "$code" == "200" || "$code" == "401" || "$code" == "403" ]]; then
      return 0
    fi
    /bin/sleep 2
  done
  echo "ERROR: node2 did not become ready in time"
  return 1
}

store_with_retry() {
  local payload_file="$1"
  local body_file="$2"
  local attempts=20
  local last_code=""
  for attempt in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code=$(signed_json_call 1 POST "http://localhost:8082" "/recovery/fragments" "$payload_file" "$body_file" || true)
    code=$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')
    if [[ "$code" == "201" || "$code" == "409" ]]; then
      return 0
    fi
    last_code="$code"
    /bin/sleep 2
  done

  echo "ERROR: store failed after retries (last HTTP: ${last_code:-none})"
  if [[ -f "$body_file" ]]; then
    echo "Last response body: $(cat "$body_file")"
  fi
  return 1
}

FRAGMENT_ID="smoke-fragment-$(/bin/date +%s)-$RANDOM"
PAYLOAD_TEXT="recovery-bytes-smoke-$RANDOM"
PAYLOAD_BASE64=$(/usr/bin/printf "%s" "$PAYLOAD_TEXT" | /usr/bin/base64 | /usr/bin/tr -d '\n')
PAYLOAD_CHECKSUM=$(sha256_hex "$PAYLOAD_TEXT")
REQUEST_JSON="$TMP_DIR/recovery-store.json"
STORE_BODY="$TMP_DIR/recovery-store-body.json"

/usr/bin/jq -n \
  --arg fragmentId "$FRAGMENT_ID" \
  --arg requesterPublicKey "$REQUESTER_PUBLIC_KEY" \
  --arg checksum "$PAYLOAD_CHECKSUM" \
  --arg payloadBase64 "$PAYLOAD_BASE64" \
  '{
    fragmentId: $fragmentId,
    agreementId: "smoke-agreement-1",
    requesterNodeId: "node-smoke-1",
    requesterPublicKey: $requesterPublicKey,
    checksumAlgorithm: "SHA-256",
    checksum: $checksum,
    payloadBase64: $payloadBase64
  }' > "$REQUEST_JSON"

echo "==> Waiting for node2 readiness"
wait_for_node_ready

echo "==> Storing recovery fragment with payload (retrying until ready)"
store_with_retry "$REQUEST_JSON" "$STORE_BODY"

echo "==> Downloading recovery payload bytes"
CONTENT_FILE="$TMP_DIR/recovery-content.bin"
CONTENT_HEADERS="$TMP_DIR/recovery-content.headers"
CONTENT_CODE=$(signed_binary_get 1 "http://localhost:8082" "/recovery/fragments/${FRAGMENT_ID}/content" "$CONTENT_FILE" "$CONTENT_HEADERS")
[[ "$CONTENT_CODE" == "200" ]] || { echo "ERROR: content download failed HTTP $CONTENT_CODE"; exit 1; }

DOWNLOADED_TEXT=$(/bin/cat "$CONTENT_FILE")
if [[ "$DOWNLOADED_TEXT" != "$PAYLOAD_TEXT" ]]; then
  echo "ERROR: downloaded payload mismatch"
  echo "Expected: $PAYLOAD_TEXT"
  echo "Actual:   $DOWNLOADED_TEXT"
  exit 1
fi

if ! /usr/bin/grep -qi "^X-Size-Bytes: " "$CONTENT_HEADERS"; then
  echo "ERROR: X-Size-Bytes header missing"
  exit 1
fi

echo "SUCCESS: recovery byte custody and content download validated"

if [[ "$KEEP_RUNNING" != "true" ]]; then
  echo "==> Stopping cluster"
  "$DOCKER_BIN" compose down -v
else
  echo "==> Keeping cluster running (--keep-running)"
fi
