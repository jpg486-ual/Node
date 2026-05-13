#!/usr/bin/env bash
# Smoke recovery con simulación de outage:
#   1) node1 → node2 (tutor) almacena fragment.
#   2) Stop node3 (outage) → node1 recupera fragment desde node2.
#   3) Restart node3 → node3 recupera y verifica el contenido.
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

Full recovery smoke flow:
1) node1 -> node2 (tutor) stores fragment payload
2) node3 is stopped (simulated outage)
3) node1 recovers payload from node2
4) node3 is restarted
5) node3 recovers payload from node2 and verifies content
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
  "$DOCKER_BIN" compose up -d --force-recreate
else
  echo "==> Building and starting cluster"
  "$DOCKER_BIN" compose up --build -d --force-recreate
fi

wait_for_http() {
  local base_url="$1"
  local label="$2"
  # 60 intentos x 2s = 120s max. Margen amplio para CI saturado: Spring Boot
  # 3.5 + connect-pool a Postgres puede superar facilmente los 60s en runners
  # compartidos cuando arranca un container restartado tras un compose start.
  local attempts=60

  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code=$(/usr/bin/curl -sS -o /dev/null -w "%{http_code}" "$base_url/actuator/health" || true)
    code=$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')
    if [[ "$code" == "200" || "$code" == "401" || "$code" == "403" ]]; then
      echo "==> $label ready (health HTTP $code)"
      return 0
    fi
    /bin/sleep 2
  done

  echo "ERROR: $label did not become ready in time"
  return 1
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

wait_for_tutor_store() {
  local payload_file="$1"
  local body_file="$2"
  local attempts=20
  local last_code=""

  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code=$(signed_json_call 1 POST "http://localhost:8082" "/recovery/fragments" "$payload_file" "$body_file" || true)
    code=$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')
    if [[ "$code" == "201" || "$code" == "409" ]]; then
      return 0
    fi
    last_code="$code"
    /bin/sleep 2
  done

  echo "ERROR: tutor store failed after retries (last HTTP: ${last_code:-none})"
  echo "Last response body: $(cat "$body_file")"
  return 1
}

FRAGMENT_ID="full-flow-fragment-$(/bin/date +%s)-$RANDOM"
PAYLOAD_TEXT="full-flow-recovery-payload-$RANDOM"
PAYLOAD_BASE64=$(/usr/bin/printf "%s" "$PAYLOAD_TEXT" | /usr/bin/base64 | /usr/bin/tr -d '\n')
PAYLOAD_CHECKSUM=$(sha256_hex "$PAYLOAD_TEXT")

TUTOR_STORE_JSON="$TMP_DIR/tutor-store.json"
TUTOR_STORE_BODY="$TMP_DIR/tutor-store-body.json"

/usr/bin/jq -n \
  --arg fragmentId "$FRAGMENT_ID" \
  --arg requesterPublicKey "$REQUESTER_PUBLIC_KEY" \
  --arg checksum "$PAYLOAD_CHECKSUM" \
  --arg payloadBase64 "$PAYLOAD_BASE64" \
  '{
    fragmentId: $fragmentId,
    agreementId: "full-flow-agreement",
    requesterNodeId: "node1",
    requesterPublicKey: $requesterPublicKey,
    checksumAlgorithm: "SHA-256",
    checksum: $checksum,
    payloadBase64: $payloadBase64
  }' > "$TUTOR_STORE_JSON"

wait_for_http "http://localhost:8081" "node1"
wait_for_http "http://localhost:8082" "node2"
wait_for_http "http://localhost:8083" "node3"

detect_recovery_nodes() {
  local enabled=()
  local probe_codes=()
  local ports=(8081 8082 8083)
  local names=(node1 node2 node3)

  for idx in 0 1 2; do
    local probe_json="$TMP_DIR/recovery-probe-${names[$idx]}.json"
    local probe_body="$TMP_DIR/recovery-probe-${names[$idx]}-body.json"
    local probe_payload="probe-${names[$idx]}-$RANDOM"
    local probe_payload_b64
    probe_payload_b64=$(/usr/bin/printf "%s" "$probe_payload" | /usr/bin/base64 | /usr/bin/tr -d '\n')
    local probe_checksum
    probe_checksum=$(sha256_hex "$probe_payload")

    /usr/bin/jq -n \
      --arg fragmentId "probe-${names[$idx]}-$(/bin/date +%s)-$RANDOM" \
      --arg requesterPublicKey "$REQUESTER_PUBLIC_KEY" \
      --arg checksum "$probe_checksum" \
      --arg payloadBase64 "$probe_payload_b64" \
      '{
        fragmentId: $fragmentId,
        agreementId: "recovery-probe",
        requesterNodeId: "node1",
        requesterPublicKey: $requesterPublicKey,
        checksumAlgorithm: "SHA-256",
        checksum: $checksum,
        payloadBase64: $payloadBase64
      }' > "$probe_json"

    local code
    code=$(signed_json_call 1 POST "http://localhost:${ports[$idx]}" "/recovery/fragments" "$probe_json" "$probe_body" || true)
    code=$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')
    probe_codes+=("${names[$idx]}:${code:-none}")
    if [[ "$code" == "201" || "$code" == "409" ]]; then
      enabled+=("${names[$idx]}:${ports[$idx]}")
    fi
  done

  if [[ ${#enabled[@]} -eq 0 ]]; then
    echo "ERROR: no node accepted recovery probe store"
    echo "Probe HTTP codes by node: ${probe_codes[*]}"
    echo "Hint: this can be caused by signature/auth/whitelist issues even when recovery is enabled"
    return 1
  fi

  echo "==> Recovery-enabled nodes detected: ${enabled[*]}"
  return 0
}

detect_recovery_nodes

echo "==> Step 1/5: node1 stores fragment in node2 tutor custody"
wait_for_tutor_store "$TUTOR_STORE_JSON" "$TUTOR_STORE_BODY"

echo "==> Step 2/5: simulate node3 outage"
"$DOCKER_BIN" compose stop node3 >/dev/null

echo "==> Step 3/5: node1 recovers bytes from node2 tutor"
NODE1_RECOVERY_BODY="$TMP_DIR/node1-recovery-content.bin"
NODE1_RECOVERY_HEADERS="$TMP_DIR/node1-recovery-content.headers"
NODE1_RECOVERY_CODE=$(signed_binary_get 1 "http://localhost:8082" "/recovery/fragments/${FRAGMENT_ID}/content" "$NODE1_RECOVERY_BODY" "$NODE1_RECOVERY_HEADERS")
[[ "$NODE1_RECOVERY_CODE" == "200" ]] || { echo "ERROR: node1 recovery download failed HTTP $NODE1_RECOVERY_CODE"; exit 1; }
RECOVERED_TEXT=$(/bin/cat "$NODE1_RECOVERY_BODY")
[[ "$RECOVERED_TEXT" == "$PAYLOAD_TEXT" ]] || { echo "ERROR: recovered bytes mismatch"; exit 1; }

echo "==> Step 4/5: bring node3 back online"
# --wait espera al healthcheck del container antes de devolver el control, lo
# que evita arrancar el polling HTTP contra un proceso que aun no ha iniciado
# el listener de Tomcat. Con esto el wait_for_http subsiguiente solo cubre el
# tiempo entre healthcheck OK y /actuator/health respondiendo.
"$DOCKER_BIN" compose start --wait node3 >/dev/null
wait_for_http "http://localhost:8083" "node3 (restarted)"

echo "==> Step 5/5: node3 recovers bytes from node2 tutor and verifies"
NODE3_RECOVERY_BODY="$TMP_DIR/node3-recovery-content.bin"
NODE3_RECOVERY_HEADERS="$TMP_DIR/node3-recovery-content.headers"
NODE3_RECOVERY_CODE=$(signed_binary_get 3 "http://localhost:8082" "/recovery/fragments/${FRAGMENT_ID}/content" "$NODE3_RECOVERY_BODY" "$NODE3_RECOVERY_HEADERS")
[[ "$NODE3_RECOVERY_CODE" == "200" ]] || { echo "ERROR: node3 recovery download failed HTTP $NODE3_RECOVERY_CODE"; exit 1; }
NODE3_TEXT=$(/bin/cat "$NODE3_RECOVERY_BODY")
[[ "$NODE3_TEXT" == "$PAYLOAD_TEXT" ]] || { echo "ERROR: node3 recovered bytes mismatch"; exit 1; }

echo "SUCCESS: full recovery flow validated (store -> outage -> recover -> restart -> recover)"

if [[ "$KEEP_RUNNING" != "true" ]]; then
  echo "==> Stopping cluster"
  "$DOCKER_BIN" compose down -v
else
  echo "==> Keeping cluster running (--keep-running)"
fi
