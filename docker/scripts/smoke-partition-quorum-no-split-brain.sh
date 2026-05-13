#!/usr/bin/env bash
# Smoke "no split-brain": valida la invariante "una tabla = un dominio = un port".
# El escalation custody → recovery_orphan_fragment DEBE mover (no copiar): post
# RETURN_TO_TUTOR el fragment vive sólo en recovery_orphan_fragment@tutor y desaparece
# de custody_fragment@custodian. Una regresión a "copy" produciría double-storage
# cross-table (split-brain a nivel BD), detectado por SQL pre/post + métricas +
# idempotency check del segundo probe-now sobre sesión ESCALATED.
# Flags: [--no-build] [--keep-running] [--fast]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"
OVERRIDE_COMPOSE="$TMP_DIR/liveness-override.yml"

KEEP_RUNNING="false"
SKIP_BUILD="false"
DOCKER_BIN=""
STOPPED_NODE1="false"
NODE3_ADMIN_TOKEN=""

cleanup() {
  local exit_code=$?
  if [[ -n "${DOCKER_BIN:-}" ]]; then
    if [[ "$KEEP_RUNNING" == "true" ]]; then
      if [[ "$STOPPED_NODE1" == "true" ]]; then
        "$DOCKER_BIN" start distributed-node-1 >/dev/null 2>&1 || true
      fi
      echo "==> Keeping cluster running (--keep-running)"
    else
      if [[ -f "$OVERRIDE_COMPOSE" ]]; then
        "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" down -v >/dev/null 2>&1 || true
      else
        "$DOCKER_BIN" compose down -v >/dev/null 2>&1 || true
      fi
    fi
  fi
  /bin/rm -rf "$TMP_DIR"
  return "$exit_code"
}
trap cleanup EXIT

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
"$DOCKER_BIN" info >/dev/null 2>&1 || { echo "ERROR: docker daemon is not available"; exit 1; }

if [[ ! -f "$KEYS_DIR/node1-private.der" || ! -f "$KEYS_DIR/node2-private.der" || ! -f "$KEYS_DIR/node3-private.der" ]]; then
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
else
  echo "==> Reusing existing node keys/env"
fi

NODE1_PUBLIC_KEY_B64="$(/usr/bin/tr -d '\n' < "$KEYS_DIR/node1-public.b64")"

cat > "$OVERRIDE_COMPOSE" <<'YAML'
services:
  node3:
    environment:
      NODE_FEATURES_RECOVERY_ENABLED: "true"
      NODE_CUSTODY_LIVENESS_ENABLED: "true"
      NODE_CUSTODY_LIVENESS_MAX_FAST_RETRIES: "1"
      NODE_CUSTODY_LIVENESS_FAST_RETRY_INTERVAL_SECONDS: "1"
      NODE_CUSTODY_LIVENESS_WORKER_FIXED_DELAY_MILLIS: "1000"
      NODE_CUSTODY_LIVENESS_ESCALATION_POLICY: "RETURN_TO_TUTOR"
      NODE_CUSTODY_LIVENESS_REQUEST_TIMEOUT_MILLIS: "700"
      NODE_CUSTODY_LIVENESS_REMOTE_BASE_URLS_NODE1: "http://node1:8080"
YAML

cd "$ROOT_DIR"
if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster with liveness override (no build)"
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d --force-recreate >/dev/null
else
  echo "==> Building and starting cluster with liveness override"
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up --build -d --force-recreate >/dev/null
fi

wait_for_http() {
  local base_url="$1" label="$2" attempts=45 code
  for _ in $(/usr/bin/seq 1 "$attempts"); do
    code=$(/usr/bin/curl -sS -o /dev/null -w "%{http_code}" "$base_url/actuator/health" || true)
    code="$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')"
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
  local node_index="$1" method="$2" base_url="$3" request_path="$4" payload_file="$5" body_out="$6" bearer_token="${7:-}"
  local priv_der="$KEYS_DIR/node${node_index}-private.der"
  local pub_der="$KEYS_DIR/node${node_index}-public.der"
  [[ -f "$priv_der" && -f "$pub_der" ]] || { echo "ERROR: key material not found for node${node_index}"; return 1; }

  local node_hash node_id nonce timestamp canonical_path canonical_query
  node_hash="$(/usr/bin/shasum -a 256 "$pub_der" | /usr/bin/awk '{print $1}')"
  node_id="node-${node_hash:0:24}"
  nonce="nonce-$(/bin/date +%s)-$$-$RANDOM"
  timestamp="$(/bin/date +%s)"
  canonical_path="$request_path"
  canonical_query=""
  if [[ "$request_path" == *\?* ]]; then
    canonical_path="${request_path%%\?*}"
    canonical_query="${request_path#*\?}"
  fi

  local canonical_file key_pem_file signature_bin_file
  canonical_file="$(/usr/bin/mktemp)"
  key_pem_file="$(/usr/bin/mktemp)"
  signature_bin_file="$(/usr/bin/mktemp)"
  /usr/bin/printf "%s\n%s\n%s\n%s\n%s" "$method" "$canonical_path" "$canonical_query" "$nonce" "$timestamp" > "$canonical_file"
  /usr/bin/openssl pkey -inform DER -in "$priv_der" -out "$key_pem_file" >/dev/null 2>&1
  /usr/bin/openssl dgst -sha256 -sign "$key_pem_file" -out "$signature_bin_file" "$canonical_file"
  local signature_base64
  signature_base64="$(/usr/bin/base64 < "$signature_bin_file" | /usr/bin/tr -d '\n')"

  local curl_args
  curl_args=(
    /usr/bin/curl -sS -i -X "$method" "$base_url$request_path"
    -H "Accept: application/json"
    -H "X-Node-Id: $node_id"
    -H "X-Nonce: $nonce"
    -H "X-Timestamp: $timestamp"
    -H "X-Signature-Algorithm: SHA256withECDSA"
    -H "X-Signature: $signature_base64"
  )
  [[ -n "$bearer_token" ]] && curl_args+=( -H "Authorization: Bearer $bearer_token" )
  [[ -n "$payload_file" ]] && curl_args+=( -H "Content-Type: application/json" --data @"$payload_file" )

  local raw_response
  raw_response="$("${curl_args[@]}" || true)"
  /bin/rm -f "$canonical_file" "$key_pem_file" "$signature_bin_file"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

signed_get_call() {
  signed_json_call "$1" GET "$2" "$3" "" "$4" "${5:-}"
}

plain_json_call() {
  local method="$1" base_url="$2" request_path="$3" payload_file="$4" body_out="$5" bearer_token="${6:-}"
  local curl_args raw_response
  curl_args=( /usr/bin/curl -sS -i -X "$method" "$base_url$request_path" -H "Accept: application/json" )
  [[ -n "$bearer_token" ]] && curl_args+=( -H "Authorization: Bearer $bearer_token" )
  [[ -n "$payload_file" ]] && curl_args+=( -H "Content-Type: application/json" --data @"$payload_file" )
  raw_response="$("${curl_args[@]}" || true)"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

json_read() {
  /usr/bin/python3 - "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1], 'r', encoding='utf-8') as fh:
    data = json.load(fh)
expr = sys.argv[2]
safe = {'int': int, 'len': len, 'isinstance': isinstance, 'list': list, 'dict': dict, 'str': str}
value = eval(expr, {'__builtins__': safe}, {'data': data})
if isinstance(value, bool):
    print('true' if value else 'false')
elif value is None:
    print('')
else:
    print(value)
PY
}

url_encode() {
  /usr/bin/python3 - "$1" <<'PY'
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=''))
PY
}

sql_count() {
  local node="$1" sql="$2"
  "$DOCKER_BIN" exec "node-postgres-$node" psql -U node -d node -t -A -c "$sql" 2>/dev/null | tr -d '[:space:]'
}

provision_node3_admin_token() {
  local admin_code admin_username admin_password="Passw0rd!"
  admin_code="SMK$RANDOM$RANDOM"
  admin_username="smoke-admin-$(/bin/date +%s)-$RANDOM"

  "$DOCKER_BIN" exec node-postgres-3 psql -v ON_ERROR_STOP=1 -U node -d node -c \
    "insert into registration_code(code, quota_mb, expires_at, used, used_at, created_at, role)\
     values ('$admin_code', 1024, now() + interval '2 hour', false, null, now(), 'NODE_ADMIN')\
     on conflict (code) do update set quota_mb=excluded.quota_mb, expires_at=excluded.expires_at,\
       used=false, used_at=null, created_at=excluded.created_at, role=excluded.role;" >/dev/null

  local register_json="$TMP_DIR/auth-register-admin.json"
  local register_body="$TMP_DIR/auth-register-admin-body.json"
  /usr/bin/jq -n --arg invitationCode "$admin_code" --arg username "$admin_username" --arg password "$admin_password" \
    '{invitationCode: $invitationCode, username: $username, password: $password}' > "$register_json"

  local register_code
  register_code="$(plain_json_call POST "http://localhost:8083" "/auth/register" "$register_json" "$register_body")"
  register_code="$(/usr/bin/printf "%s" "$register_code" | /usr/bin/tr -d '\r\n')"
  [[ "$register_code" == "201" ]] || { echo "ERROR: register failed (HTTP $register_code) body=$(/bin/cat "$register_body")"; return 1; }

  local login_json="$TMP_DIR/auth-login-admin.json"
  local login_body="$TMP_DIR/auth-login-admin-body.json"
  /usr/bin/jq -n --arg username "$admin_username" --arg password "$admin_password" \
    '{username: $username, password: $password}' > "$login_json"

  local login_code
  login_code="$(plain_json_call POST "http://localhost:8083" "/auth/login" "$login_json" "$login_body")"
  login_code="$(/usr/bin/printf "%s" "$login_code" | /usr/bin/tr -d '\r\n')"
  [[ "$login_code" == "200" ]] || { echo "ERROR: login failed (HTTP $login_code) body=$(/bin/cat "$login_body")"; return 1; }

  NODE3_ADMIN_TOKEN="$(json_read "$login_body" "data.get('token', '')")"
  NODE3_ADMIN_TOKEN="$(/usr/bin/printf "%s" "$NODE3_ADMIN_TOKEN" | /usr/bin/tr -d '\r\n')"
  [[ -n "$NODE3_ADMIN_TOKEN" ]] || { echo "ERROR: admin login no token"; return 1; }
}

wait_for_session_status() {
  local session_id="$1" expected_status="$2" body_out="$3" bearer_token="$4"
  for _ in $(/usr/bin/seq 1 50); do
    local code status
    code="$(signed_get_call 3 "http://localhost:8083" "/ops/custody-liveness/sessions/$session_id" "$body_out" "$bearer_token")"
    code="$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')"
    if [[ "$code" == "200" ]]; then
      status="$(json_read "$body_out" "data.get('status', '')")"
      status="$(/usr/bin/printf "%s" "$status" | /usr/bin/tr -d '\r\n')"
      [[ "$status" == "$expected_status" ]] && return 0
    fi
    /bin/sleep 1
  done
  return 1
}

wait_for_http "http://localhost:8081" "node1"
wait_for_http "http://localhost:8082" "node2"
wait_for_http "http://localhost:8083" "node3"

echo "==> Provisioning node3 admin session for /ops access"
provision_node3_admin_token

NODE1_HASH=$(/usr/bin/shasum -a 256 "$KEYS_DIR/node1-public.der" | /usr/bin/awk '{print $1}')
REMOTE_NODE_ID="node-${NODE1_HASH:0:24}"
REMOTE_ENCODED="$(url_encode "$REMOTE_NODE_ID")"
FRAGMENT_ID="no-dup-$(/bin/date +%s)-$RANDOM"
PAYLOAD_TEXT="no-dup-payload-$RANDOM"
PAYLOAD_CHECKSUM="$(/usr/bin/printf "%s" "$PAYLOAD_TEXT" | /usr/bin/shasum -a 256 | /usr/bin/awk '{print $1}')"

echo "==> Step 1/8: read baseline metric escalated_total"
METRICS_BASELINE_BODY="$TMP_DIR/metrics-baseline.json"
METRICS_CODE="$(signed_get_call 3 "http://localhost:8083" "/ops/custody-liveness/metrics" "$METRICS_BASELINE_BODY" "$NODE3_ADMIN_TOKEN")"
METRICS_CODE="$(/usr/bin/printf "%s" "$METRICS_CODE" | /usr/bin/tr -d '\r\n')"
[[ "$METRICS_CODE" == "200" ]] || { echo "ERROR: metrics baseline failed HTTP $METRICS_CODE"; cat "$METRICS_BASELINE_BODY"; exit 1; }
BASE_ESCALATED="$(json_read "$METRICS_BASELINE_BODY" "int(data.get('metrics', {}).get('custody.liveness.transition.escalated.total', 0))")"

echo "==> Step 2/8: seed custody fragment on node3 (custody domain)"
PAYLOAD_BIN="$TMP_DIR/payload.bin"
/usr/bin/printf "%s" "$PAYLOAD_TEXT" > "$PAYLOAD_BIN"
STORE_BODY="$TMP_DIR/store-body.json"
STORE_NONCE="custody-store-$(/bin/date +%s)-$$-$RANDOM"
STORE_TIMESTAMP="$(/bin/date +%s)"
STORE_CANONICAL="$TMP_DIR/store-canonical.txt"
STORE_PEM="$TMP_DIR/store-key.pem"
STORE_SIG_BIN="$TMP_DIR/store-signature.bin"
/usr/bin/printf "%s\n%s\n%s\n%s\n%s" "POST" "/custody/fragments" "" "$STORE_NONCE" "$STORE_TIMESTAMP" > "$STORE_CANONICAL"
/usr/bin/openssl pkey -inform DER -in "$KEYS_DIR/node1-private.der" -out "$STORE_PEM" >/dev/null 2>&1
/usr/bin/openssl dgst -sha256 -sign "$STORE_PEM" -out "$STORE_SIG_BIN" "$STORE_CANONICAL"
STORE_SIG_B64=$(/usr/bin/base64 < "$STORE_SIG_BIN" | /usr/bin/tr -d '\n')

STORE_CODE=$(/usr/bin/curl -sS -o "$STORE_BODY" -w '%{http_code}' \
  -X POST "http://localhost:8083/custody/fragments" \
  -H "Content-Type: application/octet-stream" \
  -H "X-Node-Id: $REMOTE_NODE_ID" \
  -H "X-Nonce: $STORE_NONCE" \
  -H "X-Timestamp: $STORE_TIMESTAMP" \
  -H "X-Signature-Algorithm: SHA256withECDSA" \
  -H "X-Signature: $STORE_SIG_B64" \
  -H "X-Fragment-Id: $FRAGMENT_ID" \
  -H "X-Agreement-Id: no-dup-agreement" \
  -H "X-Sender-Node-Id: $REMOTE_NODE_ID" \
  -H "X-Sender-Public-Key: $NODE1_PUBLIC_KEY_B64" \
  -H "X-Checksum-Algorithm: SHA-256" \
  -H "X-Checksum: $PAYLOAD_CHECKSUM" \
  -H "X-Custody-Seconds: 900" \
  --data-binary @"$PAYLOAD_BIN")
STORE_CODE="$(/usr/bin/printf "%s" "$STORE_CODE" | /usr/bin/tr -d '\r\n')"
[[ "$STORE_CODE" == "201" || "$STORE_CODE" == "409" ]] || {
  echo "ERROR: custody store failed HTTP $STORE_CODE body=$(/bin/cat "$STORE_BODY")"; exit 1
}

echo "==> Step 3/8: assert pre-condition — custody@node3=1, recovery_orphan@*=0"
PRE_CUSTODY_N3=$(sql_count 3 "select count(*) from custody_fragment where fragment_id='$FRAGMENT_ID';")
PRE_ORPHAN_N1=$(sql_count 1 "select count(*) from recovery_orphan_fragment where fragment_id='$FRAGMENT_ID';")
PRE_ORPHAN_N2=$(sql_count 2 "select count(*) from recovery_orphan_fragment where fragment_id='$FRAGMENT_ID';")
PRE_ORPHAN_N3=$(sql_count 3 "select count(*) from recovery_orphan_fragment where fragment_id='$FRAGMENT_ID';")
echo "  custody@node3=$PRE_CUSTODY_N3, recovery_orphan@node1=$PRE_ORPHAN_N1, @node2=$PRE_ORPHAN_N2, @node3=$PRE_ORPHAN_N3"
[[ "$PRE_CUSTODY_N3" == "1" ]] || { echo "ERROR: expected custody@node3=1, got $PRE_CUSTODY_N3"; exit 1; }
[[ "$PRE_ORPHAN_N1" == "0" && "$PRE_ORPHAN_N2" == "0" && "$PRE_ORPHAN_N3" == "0" ]] || {
  echo "ERROR: pre-condition violated — recovery_orphan should be empty pre-escalation"; exit 1
}

echo "==> Step 4/8: stop node1 to force unresponsive probe"
"$DOCKER_BIN" stop distributed-node-1 >/dev/null
STOPPED_NODE1="true"

echo "==> Step 5/8: trigger probe-now → expect ESCALATED"
PROBE_BODY="$TMP_DIR/probe-body.json"
EMPTY_JSON="$TMP_DIR/empty.json"
echo '{}' > "$EMPTY_JSON"
PROBE_CODE="$(signed_json_call 3 POST "http://localhost:8083" "/ops/custody-liveness/remote/$REMOTE_ENCODED/probe-now" "$EMPTY_JSON" "$PROBE_BODY" "$NODE3_ADMIN_TOKEN")"
PROBE_CODE="$(/usr/bin/printf "%s" "$PROBE_CODE" | /usr/bin/tr -d '\r\n')"
[[ "$PROBE_CODE" == "200" ]] || { echo "ERROR: probe-now failed HTTP $PROBE_CODE body=$(/bin/cat "$PROBE_BODY")"; exit 1; }
SESSION_ID="$(json_read "$PROBE_BODY" "data.get('sessionId', '')")"
SESSION_ID="$(/usr/bin/printf "%s" "$SESSION_ID" | /usr/bin/tr -d '\r\n')"
[[ -n "$SESSION_ID" ]] || { echo "ERROR: probe response missing sessionId body=$(/bin/cat "$PROBE_BODY")"; exit 1; }

SESSION_BODY="$TMP_DIR/session-body.json"
wait_for_session_status "$SESSION_ID" "ESCALATED" "$SESSION_BODY" "$NODE3_ADMIN_TOKEN" || {
  echo "ERROR: session never reached ESCALATED body=$(/bin/cat "$SESSION_BODY")"; exit 1
}
echo "  session $SESSION_ID reached ESCALATED"

echo "==> Step 6/8: assert post-condition — custody@*=0, recovery_orphan@node2=1"
# Invariante: el escalation MUEVE (no copia) custody → recovery_orphan@tutor.
POST_CUSTODY_N1=$(sql_count 1 "select count(*) from custody_fragment where fragment_id='$FRAGMENT_ID';")
POST_CUSTODY_N2=$(sql_count 2 "select count(*) from custody_fragment where fragment_id='$FRAGMENT_ID';")
POST_CUSTODY_N3=$(sql_count 3 "select count(*) from custody_fragment where fragment_id='$FRAGMENT_ID';")
POST_ORPHAN_N1=$(sql_count 1 "select count(*) from recovery_orphan_fragment where fragment_id='$FRAGMENT_ID';")
POST_ORPHAN_N2=$(sql_count 2 "select count(*) from recovery_orphan_fragment where fragment_id='$FRAGMENT_ID';")
POST_ORPHAN_N3=$(sql_count 3 "select count(*) from recovery_orphan_fragment where fragment_id='$FRAGMENT_ID';")
echo "  custody@node1=$POST_CUSTODY_N1, @node2=$POST_CUSTODY_N2, @node3=$POST_CUSTODY_N3"
echo "  recovery_orphan@node1=$POST_ORPHAN_N1, @node2=$POST_ORPHAN_N2, @node3=$POST_ORPHAN_N3"
# node1 is stopped, but custody/orphan tables shouldn't have it anyway.
[[ "$POST_CUSTODY_N2" == "0" && "$POST_CUSTODY_N3" == "0" ]] || {
  echo "ERROR: split-brain — custody_fragment retains row post-escalation"; exit 1
}
[[ "$POST_ORPHAN_N2" == "1" ]] || {
  echo "ERROR: tutor (node2) recovery_orphan_fragment should have exactly 1 row, got $POST_ORPHAN_N2"; exit 1
}
[[ "$POST_ORPHAN_N3" == "0" ]] || {
  echo "ERROR: split-brain — recovery_orphan_fragment leaked to non-tutor node3"; exit 1
}

echo "==> Step 7/8: validate metric escalated_total incremented"
METRICS_AFTER_BODY="$TMP_DIR/metrics-after.json"
METRICS_AFTER_CODE="$(signed_get_call 3 "http://localhost:8083" "/ops/custody-liveness/metrics" "$METRICS_AFTER_BODY" "$NODE3_ADMIN_TOKEN")"
METRICS_AFTER_CODE="$(/usr/bin/printf "%s" "$METRICS_AFTER_CODE" | /usr/bin/tr -d '\r\n')"
[[ "$METRICS_AFTER_CODE" == "200" ]] || { echo "ERROR: metrics after failed HTTP $METRICS_AFTER_CODE"; exit 1; }
AFTER_ESCALATED="$(json_read "$METRICS_AFTER_BODY" "int(data.get('metrics', {}).get('custody.liveness.transition.escalated.total', 0))")"
echo "  escalated_total: baseline=$BASE_ESCALATED, after=$AFTER_ESCALATED"
[[ "$AFTER_ESCALATED" -ge $((BASE_ESCALATED + 1)) ]] || {
  echo "ERROR: escalated_total did not increase (baseline=$BASE_ESCALATED, after=$AFTER_ESCALATED)"; exit 1
}

echo "==> Step 8/8: idempotency — re-trigger probe-now over ESCALATED session, no new orphan"
PROBE2_BODY="$TMP_DIR/probe2-body.json"
PROBE2_CODE="$(signed_json_call 3 POST "http://localhost:8083" "/ops/custody-liveness/remote/$REMOTE_ENCODED/probe-now" "$EMPTY_JSON" "$PROBE2_BODY" "$NODE3_ADMIN_TOKEN")"
PROBE2_CODE="$(/usr/bin/printf "%s" "$PROBE2_CODE" | /usr/bin/tr -d '\r\n')"
# 200 OK con dedup, 409 si la sesión está terminal — ambos aceptables; lo crítico es
# que el row count de recovery_orphan@node2 sigue siendo 1 (no duplicación).
[[ "$PROBE2_CODE" == "200" || "$PROBE2_CODE" == "409" ]] || {
  echo "ERROR: re-probe returned unexpected HTTP $PROBE2_CODE body=$(/bin/cat "$PROBE2_BODY")"; exit 1
}
/bin/sleep 2
IDEMPOTENT_ORPHAN_N2=$(sql_count 2 "select count(*) from recovery_orphan_fragment where fragment_id='$FRAGMENT_ID';")
[[ "$IDEMPOTENT_ORPHAN_N2" == "1" ]] || {
  echo "ERROR: idempotency violated — re-probe duplicated recovery_orphan_fragment ($IDEMPOTENT_ORPHAN_N2 rows)"; exit 1
}
echo "  idempotency confirmed: recovery_orphan@node2=$IDEMPOTENT_ORPHAN_N2 (expected 1)"

echo
echo "SUCCESS: custody escalation no-duplication smoke passed"
echo "  - Pre-escalation: custody@node3=1, recovery_orphan@*=0"
echo "  - Post-escalation: custody@*=0, recovery_orphan@node2=1 (mover, no copiar)"
echo "  - Metric custody.liveness.transition.escalated.total incrementado"
echo "  - Probe-now idempotente sobre sesión ESCALATED"
