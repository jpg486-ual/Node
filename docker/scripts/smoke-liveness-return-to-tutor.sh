#!/usr/bin/env bash
# Smoke del flujo CustodyEscalationPolicy=RETURN_TO_TUTOR: levanta cluster con liveness +
# recovery activos en node3 (custodian), seedea custody fragment con requesterNodeId=node1,
# para node1 (origen unresponsive), dispara probe-now y verifica que la sesión llega a
# ESCALATED y el fragment migra de custody_fragment@node3 a recovery_orphan_fragment@node2.
# Flags: [--keep-running] [--no-build] [--fast] [--bootstrap-*]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"
trap '/bin/rm -rf "$TMP_DIR"' EXIT

KEEP_RUNNING="false"
SKIP_BUILD="false"
BOOTSTRAP_SCRIPT="none"

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

Liveness RETURN_TO_TUTOR smoke flow:
1) Start cluster with temporary override enabling liveness+recovery on node3
2) Store recovery fragment on node3 with requesterNodeId set to http://node1:8080
3) Stop node1 to force outbound probe failure
4) Trigger immediate probe on node3
5) Verify session reaches ESCALATED
6) Verify fragment is present in tutor node2 and removed from node3

Bootstrap options:
  --bootstrap-e2e            Reuse docker/scripts/smoke-e2e.sh --fast as cluster bootstrap.
  --bootstrap-recovery-full  Reuse docker/scripts/smoke-recovery-full-flow.sh --fast as cluster bootstrap.
  --bootstrap-recovery-bytes Reuse docker/scripts/smoke-recovery-bytes.sh --fast as cluster bootstrap.
  --bootstrap-recovery-octet Reuse docker/scripts/smoke-recovery-octet-stream.sh --fast as cluster bootstrap.
EOF
      exit 0
      ;;
    --bootstrap-e2e)
      BOOTSTRAP_SCRIPT="smoke-e2e.sh"
      SKIP_BUILD="true"
      ;;
    --bootstrap-recovery-full)
      BOOTSTRAP_SCRIPT="smoke-recovery-full-flow.sh"
      SKIP_BUILD="true"
      ;;
    --bootstrap-recovery-bytes)
      BOOTSTRAP_SCRIPT="smoke-recovery-bytes.sh"
      SKIP_BUILD="true"
      ;;
    --bootstrap-recovery-octet)
      BOOTSTRAP_SCRIPT="smoke-recovery-octet-stream.sh"
      SKIP_BUILD="true"
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

if [[ ! -f "$KEYS_DIR/node1-private.der" || ! -f "$KEYS_DIR/node2-private.der" || ! -f "$KEYS_DIR/node3-private.der" ]]; then
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
else
  echo "==> Reusing existing node keys/env"
fi

NODE1_PUBLIC_KEY_B64="$(/usr/bin/tr -d '\n' < "$KEYS_DIR/node1-public.b64")"

if [[ "$BOOTSTRAP_SCRIPT" != "none" ]]; then
  echo "==> Bootstrapping cluster using docker/scripts/$BOOTSTRAP_SCRIPT --fast"
  "$DOCKER_DIR/scripts/$BOOTSTRAP_SCRIPT" --fast
fi

OVERRIDE_COMPOSE="$TMP_DIR/liveness-override.yml"
cat > "$OVERRIDE_COMPOSE" <<'YAML'
services:
  node3:
    environment:
      NODE_FEATURES_RECOVERY_ENABLED: "true"
      NODE_CUSTODY_LIVENESS_ENABLED: "true"
      NODE_CUSTODY_LIVENESS_MAX_FAST_RETRIES: "1"
      NODE_CUSTODY_LIVENESS_FAST_RETRY_INTERVAL_SECONDS: "2"
      NODE_CUSTODY_LIVENESS_WORKER_FIXED_DELAY_MILLIS: "1000"
      NODE_CUSTODY_LIVENESS_ESCALATION_POLICY: "RETURN_TO_TUTOR"
      NODE_CUSTODY_LIVENESS_REQUEST_TIMEOUT_MILLIS: "1000"
      NODE_CUSTODY_LIVENESS_REMOTE_BASE_URLS_NODE1: "http://node1:8080"
YAML

cd "$ROOT_DIR"
if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster with liveness override (no build)"
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d --force-recreate
else
  echo "==> Building and starting cluster with liveness override"
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up --build -d --force-recreate
fi

wait_for_http() {
  local base_url="$1"
  local label="$2"
  local attempts=45

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
  raw_response=$("$DOCKER_DIR/scripts/signed-request.sh" "$node_index" "$method" "$base_url" "$request_path" "$payload_file" || true)

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

signed_get_call() {
  local node_index="$1"
  local base_url="$2"
  local request_path="$3"
  local body_out="$4"

  local raw_response
  raw_response=$("$DOCKER_DIR/scripts/signed-request.sh" "$node_index" GET "$base_url" "$request_path" || true)

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

url_encode() {
  /usr/bin/python3 - "$1" <<'PY'
import sys
import urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
}

wait_for_http "http://localhost:8081" "node1"
wait_for_http "http://localhost:8082" "node2"
wait_for_http "http://localhost:8083" "node3"

FRAGMENT_ID="liveness-return-$(/bin/date +%s)-$RANDOM"
PAYLOAD_TEXT="liveness-return-payload-$RANDOM"
PAYLOAD_B64="$(/usr/bin/printf "%s" "$PAYLOAD_TEXT" | /usr/bin/base64 | /usr/bin/tr -d '\n')"
PAYLOAD_CHECKSUM="$(/usr/bin/printf "%s" "$PAYLOAD_TEXT" | /usr/bin/shasum -a 256 | /usr/bin/awk '{print $1}')"
REMOTE_URL="http://node1:8080"
# The inventory port queries by requesterNodeId from custody_fragment.
# Fragment is seeded with X-Sender-Node-Id = node1's signature node id (sha256
# prefix), so the probe-now URL must use the same canonical id.
REMOTE_NODE_ID_HASH=$(/usr/bin/shasum -a 256 "$KEYS_DIR/node1-public.der" | /usr/bin/awk '{print $1}')
REMOTE_NODE_ID="node-${REMOTE_NODE_ID_HASH:0:24}"
REMOTE_ENCODED="$(url_encode "$REMOTE_NODE_ID")"

# Seed via /custody/fragments (custody domain) so the probe inventory picks up
# the fragment. custody_fragment and recovery_orphan_fragment are physically
# separate tables and the probe cycle reads only from custody — recovery_orphan
# does not participate. The escalation moves the row from custody_fragment
# (node3) to recovery_orphan_fragment (tutor node2) via HTTP roundtrip.
PAYLOAD_BIN="$TMP_DIR/payload.bin"
/usr/bin/printf "%s" "$PAYLOAD_TEXT" > "$PAYLOAD_BIN"
STORE_BODY="$TMP_DIR/store-node3-body.json"

# The custody endpoint requires the fragment payload as octet-stream + a set of
# X- headers describing the deposit. Sign the canonical 5-field payload as
# usual; signature is over (method, path, query, nonce, timestamp), not the
# body bytes — same scheme as /recovery/fragments and other signed endpoints.
NODE1_HASH=$(/usr/bin/shasum -a 256 "$KEYS_DIR/node1-public.der" | /usr/bin/awk '{print $1}')
NODE1_ID="node-${NODE1_HASH:0:24}"
NONCE="custody-store-$(/bin/date +%s)-$$-$RANDOM"
TIMESTAMP="$(/bin/date +%s)"
CANONICAL_FILE="$(/usr/bin/mktemp)"
KEY_PEM_FILE="$(/usr/bin/mktemp)"
SIG_BIN_FILE="$(/usr/bin/mktemp)"
/usr/bin/printf "%s\n%s\n%s\n%s\n%s" "POST" "/custody/fragments" "" "$NONCE" "$TIMESTAMP" > "$CANONICAL_FILE"
/usr/bin/openssl pkey -inform DER -in "$KEYS_DIR/node1-private.der" -out "$KEY_PEM_FILE" >/dev/null 2>&1
/usr/bin/openssl dgst -sha256 -sign "$KEY_PEM_FILE" -out "$SIG_BIN_FILE" "$CANONICAL_FILE"
SIG_B64=$(/usr/bin/base64 < "$SIG_BIN_FILE" | /usr/bin/tr -d '\n')
/bin/rm -f "$CANONICAL_FILE" "$KEY_PEM_FILE" "$SIG_BIN_FILE"

echo "==> Storing fragment on node3 (custody domain)"
STORE_CODE=$(/usr/bin/curl -sS -o "$STORE_BODY" -w '%{http_code}' \
  -X POST "http://localhost:8083/custody/fragments" \
  -H "Content-Type: application/octet-stream" \
  -H "X-Node-Id: $NODE1_ID" \
  -H "X-Nonce: $NONCE" \
  -H "X-Timestamp: $TIMESTAMP" \
  -H "X-Signature-Algorithm: SHA256withECDSA" \
  -H "X-Signature: $SIG_B64" \
  -H "X-Fragment-Id: $FRAGMENT_ID" \
  -H "X-Agreement-Id: liveness-return-agreement" \
  -H "X-Sender-Node-Id: $NODE1_ID" \
  -H "X-Sender-Public-Key: $NODE1_PUBLIC_KEY_B64" \
  -H "X-Checksum-Algorithm: SHA-256" \
  -H "X-Checksum: $PAYLOAD_CHECKSUM" \
  -H "X-Custody-Seconds: 600" \
  --data-binary @"$PAYLOAD_BIN")
STORE_CODE="$(/usr/bin/printf "%s" "$STORE_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$STORE_CODE" != "201" && "$STORE_CODE" != "409" ]]; then
  echo "ERROR: failed to store custody fragment on node3 (HTTP $STORE_CODE)"
  echo "Body: $(/bin/cat "$STORE_BODY")"
  exit 1
fi

echo "==> Stopping node1 to force unresponsive probe"
"$DOCKER_BIN" stop distributed-node-1 >/dev/null

PROBE_BODY="$TMP_DIR/probe-now-body.json"
EMPTY_JSON="$TMP_DIR/empty.json"
echo "{}" > "$EMPTY_JSON"
echo "==> Triggering immediate liveness probe from node3 against $REMOTE_NODE_ID"
PROBE_CODE="$(signed_json_call 3 POST "http://localhost:8083" "/ops/custody-liveness/remote/$REMOTE_ENCODED/probe-now" "$EMPTY_JSON" "$PROBE_BODY")"
PROBE_CODE="$(/usr/bin/printf "%s" "$PROBE_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$PROBE_CODE" != "200" ]]; then
  echo "ERROR: probe-now failed (HTTP $PROBE_CODE)"
  echo "Body: $(/bin/cat "$PROBE_BODY")"
  exit 1
fi

SESSIONS_BODY="$TMP_DIR/sessions-body.json"
STATUS=""
for _ in $(/usr/bin/seq 1 40); do
  CODE="$(signed_get_call 3 "http://localhost:8083" "/ops/custody-liveness/remote/$REMOTE_ENCODED" "$SESSIONS_BODY")"
  CODE="$(/usr/bin/printf "%s" "$CODE" | /usr/bin/tr -d '\r\n')"
  if [[ "$CODE" == "200" ]]; then
    STATUS="$(/usr/bin/python3 - "$SESSIONS_BODY" <<'PY'
import json,sys
with open(sys.argv[1], 'r', encoding='utf-8') as fh:
    data = json.load(fh)
if data:
    print(data[0].get('status', ''))
PY
)"
    STATUS="$(/usr/bin/printf "%s" "$STATUS" | /usr/bin/tr -d '\r\n')"
    if [[ "$STATUS" == "ESCALATED" ]]; then
      break
    fi
  fi
  /bin/sleep 1
done

if [[ "$STATUS" != "ESCALATED" ]]; then
  echo "ERROR: expected ESCALATED status, got '${STATUS:-none}'"
  echo "Sessions: $(/bin/cat "$SESSIONS_BODY")"
  exit 1
fi

echo "==> Verifying fragment is present in tutor node2"
TUTOR_BODY="$TMP_DIR/tutor-fragment-body.json"
TUTOR_CODE="$(signed_get_call 2 "http://localhost:8082" "/recovery/fragments/$FRAGMENT_ID" "$TUTOR_BODY")"
TUTOR_CODE="$(/usr/bin/printf "%s" "$TUTOR_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$TUTOR_CODE" != "200" ]]; then
  echo "ERROR: fragment not found in tutor node2 (HTTP $TUTOR_CODE)"
  echo "Body: $(/bin/cat "$TUTOR_BODY")"
  exit 1
fi

echo "==> Verifying fragment was removed from node3"
NODE3_BODY="$TMP_DIR/node3-fragment-body.json"
NODE3_CODE="$(signed_get_call 3 "http://localhost:8083" "/recovery/fragments/$FRAGMENT_ID" "$NODE3_BODY")"
NODE3_CODE="$(/usr/bin/printf "%s" "$NODE3_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$NODE3_CODE" != "404" ]]; then
  echo "ERROR: fragment still present in node3 (HTTP $NODE3_CODE)"
  echo "Body: $(/bin/cat "$NODE3_BODY")"
  exit 1
fi

echo "==> Liveness RETURN_TO_TUTOR smoke passed"

if [[ "$KEEP_RUNNING" == "true" ]]; then
  echo "==> Keeping cluster running"
else
  echo "==> Stopping cluster"
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" down -v
fi
