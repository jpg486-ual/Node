#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"

NODE_INDEX="3"
BASE_URL=""
AUTH_TOKEN=""
AUTH_USERNAME=""
AUTH_PASSWORD=""
OUTPUT_FILE=""
ALLOW_DEGRADED="false"

require_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $cmd"
    exit 1
  fi
}

require_command curl
require_command jq
require_command openssl

usage() {
  cat <<'EOF'
Usage: docker/scripts/ops-readiness-degradation-check.sh [options]

Evalua estado readiness/health endurecido sobre /ops/system/readiness
mediante autenticacion real (firma inter-nodo + Bearer admin).

Options:
  --node-index <1|2|3>        Indice de nodo local para firma (default: 3)
  --base-url <url>            URL base objetivo (default por node-index: 8081/8082/8083)
  --token <bearer>            Token Bearer ya emitido por /auth/login
  --username <user>           Usuario para obtener token automaticamente
  --password <pass>           Password para obtener token automaticamente
  --output <file>             Archivo JSON de salida (default: logs/ops-readiness/...)
  --allow-degraded            Trata estado DEGRADED como exit code 0
  --help                      Muestra esta ayuda

Exit codes:
  0   READY (o DEGRADED si --allow-degraded)
  10  DEGRADED
  20  NOT_READY

Example:
  docker/scripts/ops-readiness-degradation-check.sh \
    --node-index 3 \
    --base-url http://localhost:8083 \
    --username node-admin \
    --password 'Passw0rd!'
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --node-index)
      NODE_INDEX="${2:-}"
      shift
      ;;
    --base-url)
      BASE_URL="${2:-}"
      shift
      ;;
    --token)
      AUTH_TOKEN="${2:-}"
      shift
      ;;
    --username)
      AUTH_USERNAME="${2:-}"
      shift
      ;;
    --password)
      AUTH_PASSWORD="${2:-}"
      shift
      ;;
    --output)
      OUTPUT_FILE="${2:-}"
      shift
      ;;
    --allow-degraded)
      ALLOW_DEGRADED="true"
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown option '$1'"
      usage
      exit 1
      ;;
  esac
  shift
done

if [[ "$NODE_INDEX" != "1" && "$NODE_INDEX" != "2" && "$NODE_INDEX" != "3" ]]; then
  echo "ERROR: --node-index must be 1, 2 or 3"
  exit 1
fi

if [[ -z "$BASE_URL" ]]; then
  case "$NODE_INDEX" in
    1) BASE_URL="http://localhost:8081" ;;
    2) BASE_URL="http://localhost:8082" ;;
    3) BASE_URL="http://localhost:8083" ;;
  esac
fi

PRIV_DER="$KEYS_DIR/node${NODE_INDEX}-private.der"
PUB_DER="$KEYS_DIR/node${NODE_INDEX}-public.der"
if [[ ! -f "$PRIV_DER" || ! -f "$PUB_DER" ]]; then
  echo "ERROR: key material not found for node${NODE_INDEX}. Run docker/scripts/generate-node-keys.sh first."
  exit 1
fi

TMP_DIR="$(/usr/bin/mktemp -d)"
cleanup() {
  /bin/rm -rf "$TMP_DIR"
}
trap cleanup EXIT

if [[ -z "$OUTPUT_FILE" ]]; then
  OUTPUT_DIR="$ROOT_DIR/logs/ops-readiness"
  /bin/mkdir -p "$OUTPUT_DIR"
  OUTPUT_FILE="$OUTPUT_DIR/readiness-snapshot-$(/bin/date +%Y%m%d-%H%M%S).json"
else
  /bin/mkdir -p "$(/usr/bin/dirname "$OUTPUT_FILE")"
fi

plain_json_call() {
  local method="$1"
  local request_path="$2"
  local payload_file="$3"
  local body_out="$4"

  local curl_args
  curl_args=(
    /usr/bin/curl -sS -i -X "$method" "$BASE_URL$request_path"
    -H "Accept: application/json"
  )
  if [[ -n "$payload_file" ]]; then
    curl_args+=( -H "Content-Type: application/json" --data @"$payload_file" )
  fi

  local raw_response
  raw_response="$("${curl_args[@]}" || true)"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

signed_get_call() {
  local request_path="$1"
  local body_out="$2"

  local node_hash
  node_hash="$(/usr/bin/shasum -a 256 "$PUB_DER" | /usr/bin/awk '{print $1}')"
  local node_id="node-${node_hash:0:24}"
  local nonce="nonce-$(/bin/date +%s)-$$-$RANDOM"
  local timestamp
  timestamp="$(/bin/date +%s)"

  local canonical_path="$request_path"
  local canonical_query=""
  if [[ "$request_path" == *\?* ]]; then
    canonical_path="${request_path%%\?*}"
    canonical_query="${request_path#*\?}"
  fi

  local canonical_file
  local key_pem_file
  local signature_bin_file
  canonical_file="$(/usr/bin/mktemp)"
  key_pem_file="$(/usr/bin/mktemp)"
  signature_bin_file="$(/usr/bin/mktemp)"

  /usr/bin/printf "%s\n%s\n%s\n%s\n%s" \
    "GET" "$canonical_path" "$canonical_query" "$nonce" "$timestamp" > "$canonical_file"
  /usr/bin/openssl pkey -inform DER -in "$PRIV_DER" -out "$key_pem_file" >/dev/null 2>&1
  /usr/bin/openssl dgst -sha256 -sign "$key_pem_file" -out "$signature_bin_file" "$canonical_file"
  local signature_base64
  signature_base64="$(/usr/bin/base64 < "$signature_bin_file" | /usr/bin/tr -d '\n')"

  local curl_args
  curl_args=(
    /usr/bin/curl -sS -i -X GET "$BASE_URL$request_path"
    -H "Accept: application/json"
    -H "X-Node-Id: $node_id"
    -H "X-Nonce: $nonce"
    -H "X-Timestamp: $timestamp"
    -H "X-Signature-Algorithm: SHA256withECDSA"
    -H "X-Signature: $signature_base64"
    -H "X-Request-Id: ops-$(/bin/date +%s)-$RANDOM"
  )
  if [[ -n "$AUTH_TOKEN" ]]; then
    curl_args+=( -H "Authorization: Bearer $AUTH_TOKEN" )
  fi

  local raw_response
  raw_response="$("${curl_args[@]}" || true)"

  /bin/rm -f "$canonical_file" "$key_pem_file" "$signature_bin_file"

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

resolve_token() {
  if [[ -n "$AUTH_TOKEN" ]]; then
    return 0
  fi

  if [[ -z "$AUTH_USERNAME" || -z "$AUTH_PASSWORD" ]]; then
    echo "ERROR: provide either --token or --username/--password"
    return 1
  fi

  local login_json="$TMP_DIR/auth-login.json"
  local login_body="$TMP_DIR/auth-login-body.json"
  /usr/bin/jq -n \
    --arg username "$AUTH_USERNAME" \
    --arg password "$AUTH_PASSWORD" \
    '{username: $username, password: $password}' > "$login_json"

  local login_code
  login_code="$(plain_json_call POST "/auth/login" "$login_json" "$login_body")"
  login_code="$(/usr/bin/printf "%s" "$login_code" | /usr/bin/tr -d '\r\n')"
  if [[ "$login_code" != "200" ]]; then
    echo "ERROR: /auth/login failed (HTTP $login_code)"
    echo "Body: $(/bin/cat "$login_body")"
    return 1
  fi

  AUTH_TOKEN="$(/usr/bin/jq -r '.token // empty' "$login_body")"
  AUTH_TOKEN="$(/usr/bin/printf "%s" "$AUTH_TOKEN" | /usr/bin/tr -d '\r\n')"
  if [[ -z "$AUTH_TOKEN" ]]; then
    echo "ERROR: /auth/login response missing token"
    echo "Body: $(/bin/cat "$login_body")"
    return 1
  fi
}

resolve_token

READINESS_BODY="$TMP_DIR/readiness.json"
READINESS_CODE="$(signed_get_call "/ops/system/readiness" "$READINESS_BODY")"
READINESS_CODE="$(/usr/bin/printf "%s" "$READINESS_CODE" | /usr/bin/tr -d '\r\n')"

if [[ "$READINESS_CODE" != "200" && "$READINESS_CODE" != "503" ]]; then
  echo "ERROR: /ops/system/readiness returned unexpected HTTP $READINESS_CODE"
  echo "Body: $(/bin/cat "$READINESS_BODY")"
  exit 1
fi

STATUS="$(/usr/bin/jq -r '.status // empty' "$READINESS_BODY")"
if [[ -z "$STATUS" ]]; then
  echo "ERROR: readiness payload missing status"
  echo "Body: $(/bin/cat "$READINESS_BODY")"
  exit 1
fi

CHECKED_AT="$(/usr/bin/jq -r '.checkedAt // empty' "$READINESS_BODY")"
DEGRADED_COUNT="$(/usr/bin/jq '[.dependencies // {} | to_entries[] | select(.value.status == "DEGRADED")] | length' "$READINESS_BODY")"
DOWN_COUNT="$(/usr/bin/jq '[.dependencies // {} | to_entries[] | select(.value.status == "DOWN")] | length' "$READINESS_BODY")"
CRITICAL_DOWN_COUNT="$(/usr/bin/jq '[.dependencies // {} | to_entries[] | select(.value.status == "DOWN" and .value.critical == true)] | length' "$READINESS_BODY")"

/bin/cp "$READINESS_BODY" "$OUTPUT_FILE"

echo "==> readiness snapshot generated"
echo "target: $BASE_URL (node-index=$NODE_INDEX)"
echo "snapshot: $OUTPUT_FILE"
echo "httpCode: $READINESS_CODE"
echo "status: $STATUS"
echo "checkedAt: ${CHECKED_AT:-unknown}"
echo "degradedDependencies: $DEGRADED_COUNT"
echo "downDependencies: $DOWN_COUNT"
echo "criticalDownDependencies: $CRITICAL_DOWN_COUNT"

echo "==> Dependency detail"
/usr/bin/jq -r '.dependencies // {} | to_entries[] | "- \(.key): status=\(.value.status) critical=\(.value.critical) detail=\(.value.detail)"' "$READINESS_BODY"

case "$STATUS" in
  READY)
    exit 0
    ;;
  DEGRADED)
    if [[ "$ALLOW_DEGRADED" == "true" ]]; then
      exit 0
    fi
    exit 10
    ;;
  NOT_READY)
    exit 20
    ;;
  *)
    echo "ERROR: unknown readiness status '$STATUS'"
    exit 1
    ;;
esac
