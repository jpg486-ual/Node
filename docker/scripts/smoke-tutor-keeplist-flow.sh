#!/usr/bin/env bash
# Smoke regression de los endpoints HTTP del flujo tutor-iniciado.
# Escenario: POST /recovery/file-manifests (custodia proactiva) +
# GET /ops/tutor/manifest-keep-list + GET /recovery/file-manifests/inventory +
# POST /recovery/fragments + POST /recovery/orphan-fragments/{id}/{claim,ack} +
# verificación 404 tras ACK.
# Flags: [--no-build] [--keep-running] [--fast]

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
NODE1_HASH=$(/usr/bin/shasum -a 256 "$KEYS_DIR/node1-public.der" | /usr/bin/awk '{print $1}')
NODE1_ID="node-${NODE1_HASH:0:24}"

cd "$ROOT_DIR"
if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster (no build)"
  "$DOCKER_BIN" compose up -d
else
  echo "==> Building and starting cluster"
  "$DOCKER_BIN" compose up --build -d
fi

wait_for_http() {
  local url="$1"
  local label="$2"
  local attempts=40
  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code=$(/usr/bin/curl -sS -o /dev/null -w "%{http_code}" "$url/actuator/health" || true)
    code=$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')
    if [[ "$code" == "200" || "$code" == "401" || "$code" == "403" ]]; then
      return 0
    fi
    /bin/sleep 2
  done
  echo "ERROR: $label not ready"
  return 1
}

extract_status() {
  /usr/bin/printf "%s" "$1" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

extract_body() {
  /usr/bin/printf "%s" "$1" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}'
}

sha256_hex() {
  /usr/bin/printf "%s" "$1" | /usr/bin/shasum -a 256 | /usr/bin/awk '{print $1}'
}

wait_for_http "http://localhost:8081" "node1"
wait_for_http "http://localhost:8082" "node2"

echo
echo "==> [1/7] POST /recovery/file-manifests (origen→tutor)"
FILE_ID=$(/usr/bin/uuidgen | /usr/bin/tr '[:upper:]' '[:lower:]')
HASH="ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
MANIFEST_JSON="$TMP_DIR/manifest.json"
/usr/bin/jq -n \
  --arg fileId "$FILE_ID" \
  --arg requesterNodeId "$NODE1_ID" \
  --arg requesterPublicKey "$REQUESTER_PUBLIC_KEY" \
  --arg hash "$HASH" \
  '{
    fileId: $fileId,
    requesterNodeId: $requesterNodeId,
    requesterPublicKey: $requesterPublicKey,
    directoryPath: "/smoke",
    originalFileName: "keeplist.bin",
    originalFileHash: $hash,
    originalSizeBytes: 4096,
    fragmentCount: 4,
    fragmentSize: 1024,
    redundancyN: 6,
    redundancyK: 4,
    fragmentHashes: [$hash, $hash, $hash, $hash],
    clientPlacementsJson: "[]"
  }' > "$MANIFEST_JSON"

RAW=$("$DOCKER_DIR/scripts/signed-request.sh" 1 POST "http://localhost:8082" "/recovery/file-manifests" "$MANIFEST_JSON")
CODE=$(extract_status "$RAW")
[[ "$CODE" == "201" ]] || { echo "ERROR: manifest store HTTP $CODE — body: $(extract_body "$RAW")"; exit 1; }
echo "    OK 201 — manifest stored"

echo
echo "==> [2/7] GET /ops/tutor/manifest-keep-list (signed, origen)"
# Origen sirve la whitelist a quien le pinge — el smoke usa node1 pingándose a sí mismo en localhost.
RAW=$("$DOCKER_DIR/scripts/signed-request.sh" 2 GET "http://localhost:8081" "/ops/tutor/manifest-keep-list")
CODE=$(extract_status "$RAW")
[[ "$CODE" == "200" ]] || { echo "ERROR: keep-list HTTP $CODE — body: $(extract_body "$RAW")"; exit 1; }
BODY=$(extract_body "$RAW")
echo "$BODY" | /usr/bin/jq -e '.fileIds | type == "array"' >/dev/null \
  || { echo "ERROR: keep-list response not array — body: $BODY"; exit 1; }
echo "    OK 200 — keep-list endpoint operative (fileIds is array)"

echo
echo "==> [3/7] GET /recovery/file-manifests/inventory (signed, tutor)"
RAW=$("$DOCKER_DIR/scripts/signed-request.sh" 1 GET "http://localhost:8082" "/recovery/file-manifests/inventory")
CODE=$(extract_status "$RAW")
[[ "$CODE" == "200" ]] || { echo "ERROR: inventory HTTP $CODE — body: $(extract_body "$RAW")"; exit 1; }
BODY=$(extract_body "$RAW")
INVENTORY=$(echo "$BODY" | /usr/bin/jq -r '.fileIds[]')
if ! echo "$INVENTORY" | /usr/bin/grep -q "^${FILE_ID}$"; then
  echo "ERROR: file_id $FILE_ID not in tutor inventory — got: $INVENTORY"
  exit 1
fi
echo "    OK 200 — inventory contains the manifest just stored"

echo
echo "==> [4/7] POST /recovery/fragments (origen→tutor, fragment)"
FRAGMENT_ID="smoke-orphan-$(/bin/date +%s)-$RANDOM"
PAYLOAD_TEXT="orphan-payload-$RANDOM"
PAYLOAD_BASE64=$(/usr/bin/printf "%s" "$PAYLOAD_TEXT" | /usr/bin/base64 | /usr/bin/tr -d '\n')
PAYLOAD_CHECKSUM=$(sha256_hex "$PAYLOAD_TEXT")
FRAGMENT_JSON="$TMP_DIR/fragment.json"
/usr/bin/jq -n \
  --arg fragmentId "$FRAGMENT_ID" \
  --arg requesterNodeId "$NODE1_ID" \
  --arg requesterPublicKey "$REQUESTER_PUBLIC_KEY" \
  --arg checksum "$PAYLOAD_CHECKSUM" \
  --arg payloadBase64 "$PAYLOAD_BASE64" \
  '{
    fragmentId: $fragmentId,
    agreementId: "smoke-keeplist-agreement",
    requesterNodeId: $requesterNodeId,
    requesterPublicKey: $requesterPublicKey,
    checksumAlgorithm: "SHA-256",
    checksum: $checksum,
    payloadBase64: $payloadBase64
  }' > "$FRAGMENT_JSON"

RAW=$("$DOCKER_DIR/scripts/signed-request.sh" 1 POST "http://localhost:8082" "/recovery/fragments" "$FRAGMENT_JSON")
CODE=$(extract_status "$RAW")
[[ "$CODE" == "201" ]] || { echo "ERROR: fragment store HTTP $CODE — body: $(extract_body "$RAW")"; exit 1; }
echo "    OK 201 — orphan fragment stored"

echo
echo "==> [5/7] POST /recovery/orphan-fragments/{id}/claim (signed, owner)"
NONCE="nonce-$(/bin/date +%s)-$RANDOM"
TIMESTAMP="$(/bin/date +%s)"
CLAIM_PATH="/recovery/orphan-fragments/${FRAGMENT_ID}/claim"
CANONICAL_FILE="$TMP_DIR/canonical-claim"
KEY_PEM_FILE="$TMP_DIR/key.pem"
SIG_BIN="$TMP_DIR/sig-claim.bin"
/usr/bin/printf "%s\n%s\n%s\n%s\n%s" "POST" "$CLAIM_PATH" "" "$NONCE" "$TIMESTAMP" > "$CANONICAL_FILE"
/usr/bin/openssl pkey -inform DER -in "$KEYS_DIR/node1-private.der" -out "$KEY_PEM_FILE" >/dev/null 2>&1
/usr/bin/openssl dgst -sha256 -sign "$KEY_PEM_FILE" -out "$SIG_BIN" "$CANONICAL_FILE"
SIG_B64=$(/usr/bin/base64 < "$SIG_BIN" | /usr/bin/tr -d '\n')

CLAIM_BODY="$TMP_DIR/claim-body.bin"
CLAIM_HEADERS="$TMP_DIR/claim-headers.txt"
CODE=$(/usr/bin/curl -sS -D "$CLAIM_HEADERS" -o "$CLAIM_BODY" -w "%{http_code}" -X POST "http://localhost:8082$CLAIM_PATH" \
  -H "Accept: application/octet-stream" \
  -H "X-Node-Id: $NODE1_ID" \
  -H "X-Nonce: $NONCE" \
  -H "X-Timestamp: $TIMESTAMP" \
  -H "X-Signature-Algorithm: SHA256withECDSA" \
  -H "X-Signature: $SIG_B64")
[[ "$CODE" == "200" ]] || { echo "ERROR: claim HTTP $CODE"; exit 1; }
DOWNLOADED=$(/bin/cat "$CLAIM_BODY")
[[ "$DOWNLOADED" == "$PAYLOAD_TEXT" ]] || { echo "ERROR: claim payload mismatch"; exit 1; }
/usr/bin/grep -qi "^X-Checksum: " "$CLAIM_HEADERS" \
  || { echo "ERROR: X-Checksum header missing on claim response"; exit 1; }
echo "    OK 200 — claim returned bytes + headers (orphan NO eliminado todavía)"

echo
echo "==> [6/7] POST /recovery/orphan-fragments/{id}/ack (signed, owner)"
ACK_PATH="/recovery/orphan-fragments/${FRAGMENT_ID}/ack"
NONCE_ACK="nonce-ack-$(/bin/date +%s)-$RANDOM"
TS_ACK="$(/bin/date +%s)"
/usr/bin/printf "%s\n%s\n%s\n%s\n%s" "POST" "$ACK_PATH" "" "$NONCE_ACK" "$TS_ACK" > "$CANONICAL_FILE"
/usr/bin/openssl dgst -sha256 -sign "$KEY_PEM_FILE" -out "$SIG_BIN" "$CANONICAL_FILE"
SIG_ACK_B64=$(/usr/bin/base64 < "$SIG_BIN" | /usr/bin/tr -d '\n')
CODE=$(/usr/bin/curl -sS -o /dev/null -w "%{http_code}" -X POST "http://localhost:8082$ACK_PATH" \
  -H "Accept: application/json" \
  -H "X-Node-Id: $NODE1_ID" \
  -H "X-Nonce: $NONCE_ACK" \
  -H "X-Timestamp: $TS_ACK" \
  -H "X-Signature-Algorithm: SHA256withECDSA" \
  -H "X-Signature: $SIG_ACK_B64")
[[ "$CODE" == "204" ]] || { echo "ERROR: ack HTTP $CODE"; exit 1; }
echo "    OK 204 — ack confirmed; tutor borró internamente"

echo
echo "==> [7/7] GET /recovery/fragments/{id} (signed, tras ACK) → 404"
NONCE_VR="nonce-verify-$(/bin/date +%s)-$RANDOM"
TS_VR="$(/bin/date +%s)"
GET_PATH="/recovery/fragments/${FRAGMENT_ID}"
/usr/bin/printf "%s\n%s\n%s\n%s\n%s" "GET" "$GET_PATH" "" "$NONCE_VR" "$TS_VR" > "$CANONICAL_FILE"
/usr/bin/openssl dgst -sha256 -sign "$KEY_PEM_FILE" -out "$SIG_BIN" "$CANONICAL_FILE"
SIG_VR_B64=$(/usr/bin/base64 < "$SIG_BIN" | /usr/bin/tr -d '\n')
CODE=$(/usr/bin/curl -sS -o /dev/null -w "%{http_code}" -X GET "http://localhost:8082$GET_PATH" \
  -H "Accept: application/json" \
  -H "X-Node-Id: $NODE1_ID" \
  -H "X-Nonce: $NONCE_VR" \
  -H "X-Timestamp: $TS_VR" \
  -H "X-Signature-Algorithm: SHA256withECDSA" \
  -H "X-Signature: $SIG_VR_B64")
[[ "$CODE" == "404" ]] || { echo "ERROR: post-ack GET HTTP $CODE (expected 404)"; exit 1; }
echo "    OK 404 — orphan no encontrado tras ACK (eliminación efectiva)"

echo
echo "SUCCESS: tutor keeplist endpoints validated end-to-end"
echo "  - POST /recovery/file-manifests       (manifest replication)"
echo "  - GET  /ops/tutor/manifest-keep-list  (whitelist invertida I5)"
echo "  - GET  /recovery/file-manifests/inventory (endpoint inverso I6)"
echo "  - POST /recovery/orphan-fragments/{id}/claim + /ack (I7 claim+ACK)"

if [[ "$KEEP_RUNNING" != "true" ]]; then
  echo "==> Stopping cluster"
  "$DOCKER_BIN" compose down -v
else
  echo "==> Keeping cluster running (--keep-running)"
fi
