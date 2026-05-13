#!/usr/bin/env bash
# Helper de firma inter-nodo ECDSA-SHA256. Construye una request firmada usando las
# claves de docker/keys/ y la deja lista para curl con headers X-Node-Id / X-Nonce /
# X-Timestamp / X-Signature-Algorithm / X-Signature.
# Uso: signed-request.sh <node_index:1|2|3> <method> <base_url> <path> [json_payload_file]

set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "Usage: $0 <node_index:1|2|3> <method> <base_url> <path> [json_payload_file]"
  exit 1
fi

NODE_INDEX="$1"
METHOD="$2"
BASE_URL="$3"
REQUEST_PATH="$4"
PAYLOAD_FILE="${5:-}"

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
KEYS_DIR="$ROOT_DIR/docker/keys"

PRIV_DER="$KEYS_DIR/node${NODE_INDEX}-private.der"
PUB_DER="$KEYS_DIR/node${NODE_INDEX}-public.der"

if [[ ! -f "$PRIV_DER" || ! -f "$PUB_DER" ]]; then
  echo "Key material not found for node${NODE_INDEX}. Run docker/scripts/generate-node-keys.sh first."
  exit 1
fi

NODE_HASH=$(/usr/bin/shasum -a 256 "$PUB_DER" | /usr/bin/awk '{print $1}')
NODE_ID="node-${NODE_HASH:0:24}"
NONCE="nonce-$(/bin/date +%s)-$$-$RANDOM"
TIMESTAMP="$(/bin/date +%s)"

CANONICAL_PATH="$REQUEST_PATH"
CANONICAL_QUERY=""
if [[ "$REQUEST_PATH" == *\?* ]]; then
  CANONICAL_PATH="${REQUEST_PATH%%\?*}"
  CANONICAL_QUERY="${REQUEST_PATH#*\?}"
fi

CANONICAL_FILE="$(/usr/bin/mktemp)"
KEY_PEM_FILE="$(/usr/bin/mktemp)"
SIGNATURE_BIN_FILE="$(/usr/bin/mktemp)"
trap '/bin/rm -f "$CANONICAL_FILE" "$KEY_PEM_FILE" "$SIGNATURE_BIN_FILE"' EXIT

/usr/bin/printf "%s\n%s\n%s\n%s\n%s" "$METHOD" "$CANONICAL_PATH" "$CANONICAL_QUERY" "$NONCE" "$TIMESTAMP" > "$CANONICAL_FILE"
/usr/bin/openssl pkey -inform DER -in "$PRIV_DER" -out "$KEY_PEM_FILE" >/dev/null 2>&1
/usr/bin/openssl dgst -sha256 -sign "$KEY_PEM_FILE" -out "$SIGNATURE_BIN_FILE" "$CANONICAL_FILE"
SIGNATURE_BASE64=$(/usr/bin/base64 < "$SIGNATURE_BIN_FILE" | /usr/bin/tr -d '\n')

if [[ -n "$PAYLOAD_FILE" ]]; then
  /usr/bin/curl -sS -i -X "$METHOD" "$BASE_URL$REQUEST_PATH" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -H "X-Node-Id: $NODE_ID" \
    -H "X-Nonce: $NONCE" \
    -H "X-Timestamp: $TIMESTAMP" \
    -H "X-Signature-Algorithm: SHA256withECDSA" \
    -H "X-Signature: $SIGNATURE_BASE64" \
    --data @"$PAYLOAD_FILE"
else
  /usr/bin/curl -sS -i -X "$METHOD" "$BASE_URL$REQUEST_PATH" \
    -H "Accept: application/json" \
    -H "X-Node-Id: $NODE_ID" \
    -H "X-Nonce: $NONCE" \
    -H "X-Timestamp: $TIMESTAMP" \
    -H "X-Signature-Algorithm: SHA256withECDSA" \
    -H "X-Signature: $SIGNATURE_BASE64"
fi
