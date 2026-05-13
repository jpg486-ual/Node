#!/usr/bin/env bash
# Smoke regression del path binario de recovery: POST /recovery/fragments con
# Content-Type=application/octet-stream + headers X-Fragment-Id/X-Checksum/etc.,
# y GET equivalente para verificar round-trip de bytes.
# Flags: [--keep-running] [--no-build] [--fast]

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
    --fast)
      KEEP_RUNNING="true"
      SKIP_BUILD="true"
      ;;
    --help)
      cat <<EOF
Usage: $0 [--keep-running] [--no-build] [--fast]

Runs a Docker smoke that validates recovery octet-stream upload and binary download.
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

sha256_hex() {
  local value="$1"
  /usr/bin/printf "%s" "$value" | /usr/bin/shasum -a 256 | /usr/bin/awk '{print $1}'
}

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

signed_binary_post() {
  local node_index="$1"
  local base_url="$2"
  local request_path="$3"
  local payload_file="$4"
  local response_body="$5"
  local headers_file="$6"
  local fragment_id="$7"
  local agreement_id="$8"
  local requester_node_id="$9"
  local requester_public_key="${10}"
  local checksum_algorithm="${11}"
  local checksum="${12}"
  local custody_seconds="${13}"

  local pub_der="$KEYS_DIR/node${node_index}-public.der"
  local node_hash
  node_hash=$(/usr/bin/shasum -a 256 "$pub_der" | /usr/bin/awk '{print $1}')
  local node_id="node-${node_hash:0:24}"
  local nonce="nonce-$(/bin/date +%s)-$RANDOM"
  local timestamp
  timestamp="$(/bin/date +%s)"

  local signature_bin="$TMP_DIR/signature-${node_index}-${RANDOM}.bin"
  create_signature "$node_index" "POST" "$request_path" "$nonce" "$timestamp" "$signature_bin"
  local signature_base64
  signature_base64=$(/usr/bin/base64 < "$signature_bin" | /usr/bin/tr -d '\n')

  /usr/bin/curl -sS -D "$headers_file" -o "$response_body" -w "%{http_code}" -X POST "$base_url$request_path" \
    -H "Content-Type: application/octet-stream" \
    -H "Accept: application/json" \
    -H "X-Node-Id: $node_id" \
    -H "X-Nonce: $nonce" \
    -H "X-Timestamp: $timestamp" \
    -H "X-Signature-Algorithm: SHA256withECDSA" \
    -H "X-Signature: $signature_base64" \
    -H "X-Fragment-Id: $fragment_id" \
    -H "X-Agreement-Id: $agreement_id" \
    -H "X-Requester-Node-Id: $requester_node_id" \
    -H "X-Requester-Public-Key: $requester_public_key" \
    -H "X-Checksum-Algorithm: $checksum_algorithm" \
    -H "X-Checksum: $checksum" \
    -H "X-Custody-Seconds: $custody_seconds" \
    --data-binary @"$payload_file"
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

FRAGMENT_ID="smoke-octet-fragment-$(/bin/date +%s)-$RANDOM"
PAYLOAD_TEXT="recovery-octet-smoke-$RANDOM"
PAYLOAD_CHECKSUM=$(sha256_hex "$PAYLOAD_TEXT")
PAYLOAD_FILE="$TMP_DIR/recovery-octet-content.bin"
STORE_BODY="$TMP_DIR/recovery-octet-store-body.json"
STORE_HEADERS="$TMP_DIR/recovery-octet-store-headers.txt"

/usr/bin/printf "%s" "$PAYLOAD_TEXT" > "$PAYLOAD_FILE"

echo "==> Waiting for node2 readiness"
wait_for_node_ready

echo "==> Uploading recovery payload via octet-stream"
STORE_CODE=$(signed_binary_post \
  1 \
  "http://localhost:8082" \
  "/recovery/fragments" \
  "$PAYLOAD_FILE" \
  "$STORE_BODY" \
  "$STORE_HEADERS" \
  "$FRAGMENT_ID" \
  "smoke-agreement-octet-1" \
  "node-smoke-1" \
  "$REQUESTER_PUBLIC_KEY" \
  "SHA-256" \
  "$PAYLOAD_CHECKSUM" \
  "300")

[[ "$STORE_CODE" == "201" || "$STORE_CODE" == "409" ]] || {
  echo "ERROR: octet-stream store failed HTTP $STORE_CODE"
  echo "Body: $(cat "$STORE_BODY")"
  exit 1
}

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

echo "SUCCESS: recovery octet-stream upload and content download validated"

if [[ "$KEEP_RUNNING" != "true" ]]; then
  echo "==> Stopping cluster"
  "$DOCKER_BIN" compose down -v
else
  echo "==> Keeping cluster running (--keep-running)"
fi
