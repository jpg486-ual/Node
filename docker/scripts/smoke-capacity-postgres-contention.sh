#!/usr/bin/env bash
# Smoke de contención concurrente sobre el ledger durable de capacidad (postgres):
# levanta cluster con capacidad reducida en node2, crea dos negotiations PENDING,
# corre los confirms en paralelo (uno gana / uno pierde), reintenta el perdedor con
# fresh signature (debe seguir fallando) y verifica estados HTTP + filas postgres.
# Flags: [--keep-running] [--no-build] [--fast]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"

KEEP_RUNNING="false"
SKIP_BUILD="false"
COMPOSE_STARTED="false"
OVERRIDE_COMPOSE=""
NODE2_CAPACITY_MAX_BYTES_OVERRIDE="100"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-running)
      KEEP_RUNNING="true"
      ;;
    --no-build)
      SKIP_BUILD="true"
      ;;
    --fast)
      KEEP_RUNNING="true"
      SKIP_BUILD="true"
      ;;
    --help)
      cat <<EOF
Usage: $0 [--keep-running] [--no-build] [--fast]

Capacity contention smoke flow (postgres durable ledger):
1) Start multi-node cluster with reduced capacity on node2
2) Create two PENDING negotiations targeting node2
3) Run both confirms concurrently to force contention
4) Retry loser confirm with fresh signature (must still fail)
5) Verify agreement states through API (one CONFIRMED, one PENDING)
6) Verify agreement states persisted in postgres
7) Verify durable capacity ledger and counter causality in postgres
8) Print success summary and stop the cluster (unless --keep-running)
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

run_compose() {
  if [[ -n "$OVERRIDE_COMPOSE" && -f "$OVERRIDE_COMPOSE" ]]; then
    "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" "$@"
  else
    "$DOCKER_BIN" compose -f docker-compose.yml "$@"
  fi
}

print_failure_diagnostics() {
  if [[ "$COMPOSE_STARTED" != "true" ]]; then
    return 0
  fi

  echo "==> Failure diagnostics: docker compose ps"
  run_compose ps || true
  echo "==> Failure diagnostics: tail distributed-node-2 logs"
  "$DOCKER_BIN" logs --tail=120 distributed-node-2 || true
}

shutdown_cluster() {
  if [[ "$COMPOSE_STARTED" != "true" ]]; then
    return 0
  fi
  echo "==> Stopping cluster"
  run_compose down -v || true
}

on_exit() {
  local exit_code=$?

  if [[ "$exit_code" -ne 0 ]]; then
    print_failure_diagnostics
  fi

  if [[ "$COMPOSE_STARTED" == "true" ]]; then
    if [[ "$KEEP_RUNNING" == "true" ]]; then
      echo "==> Keeping cluster running (--keep-running)"
    else
      shutdown_cluster
    fi
  fi

  /bin/rm -rf "$TMP_DIR"
  trap - EXIT
  exit "$exit_code"
}

trap on_exit EXIT

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

OVERRIDE_COMPOSE="$TMP_DIR/capacity-contention-override.yml"
cat > "$OVERRIDE_COMPOSE" <<YAML
services:
  node2:
    environment:
      NODE_CAPACITY_MAX_BYTES: "$NODE2_CAPACITY_MAX_BYTES_OVERRIDE"
YAML

cd "$ROOT_DIR"
if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster with capacity contention override (no build)"
  run_compose up -d --force-recreate
else
  echo "==> Building and starting cluster with capacity contention override"
  run_compose up --build -d --force-recreate
fi
COMPOSE_STARTED="true"

wait_for_http() {
  local base_url="$1"
  local label="$2"
  local attempts=45

  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code=$(/usr/bin/curl -sS -o /dev/null -w "%{http_code}" "$base_url/actuator/health" 2>/dev/null || true)
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
  if [[ -n "$payload_file" ]]; then
    raw_response=$(
      "$DOCKER_DIR/scripts/signed-request.sh" "$node_index" "$method" "$base_url" "$request_path" "$payload_file" || true
    )
  else
    raw_response=$(
      "$DOCKER_DIR/scripts/signed-request.sh" "$node_index" "$method" "$base_url" "$request_path" || true
    )
  fi

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

split_raw_response() {
  local raw_file="$1"
  local body_file="$2"

  /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' "$raw_file" > "$body_file"
  /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}' "$raw_file"
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
safe = {'isinstance': isinstance, 'bool': bool, 'len': len, 'list': list}
ok = eval(expr, {'__builtins__': safe}, {'data': data})
sys.exit(0 if ok else 1)
PY
  then
    echo "ASSERT FAIL: $label"
    echo "Body: $(/bin/cat "$json_file")"
    return 1
  fi
  echo "ASSERT OK: $label"
}

node_id_for_index() {
  local node_index="$1"
  local pub_der="$KEYS_DIR/node${node_index}-public.der"
  local node_hash
  node_hash=$(/usr/bin/shasum -a 256 "$pub_der" | /usr/bin/awk '{print $1}')
  /usr/bin/printf "node-%s" "${node_hash:0:24}"
}

extract_json_string_field() {
  local json_file="$1"
  local field_name="$2"
  /usr/bin/python3 - "$json_file" "$field_name" <<'PY'
import json
import sys

with open(sys.argv[1], 'r', encoding='utf-8') as fh:
    payload = json.load(fh)
value = payload.get(sys.argv[2], '')
print(value if isinstance(value, str) else '')
PY
}

wait_for_http "http://localhost:8081" "node1"
wait_for_http "http://localhost:8082" "node2"
wait_for_http "http://localhost:8083" "node3"

REQUESTER_NODE1_ID="$(node_id_for_index 1)"
REQUESTER_NODE2_ID="$(node_id_for_index 2)"
REQUESTER_NODE3_ID="$(node_id_for_index 3)"

echo "==> Step 1/8: seed compatible queued fragment in node2"
"$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -c \
  "insert into queued_fragment(bucket_size, queued_at) values (4096, now());" >/dev/null

NEGOTIATE_CONFIRM_PAYLOAD="$TMP_DIR/negotiate-confirm.json"
/usr/bin/jq -n '{ targetSignature: "smoke-target-signature" }' > "$NEGOTIATE_CONFIRM_PAYLOAD"

build_negotiate_payload() {
  local requester_node_id="$1"
  local payload_file="$2"

  /usr/bin/jq -n \
    --arg requesterNodeId "$requester_node_id" \
    --arg targetNodeId "$REQUESTER_NODE2_ID" \
    '{
      requesterNodeId: $requesterNodeId,
      targetNodeId: $targetNodeId,
      bucketSize: 4096,
      expectedStorageBytes: 21,
      transferMode: "FRAGMENTS_ONLY",
      fragmentCount: 1,
      redundancyScheme: "RS(2,1)",
      expirationSeconds: 300,
      requesterSignature: "smoke-requester-signature"
    }' > "$payload_file"
}

NEGOTIATE_CREATE_1_PAYLOAD="$TMP_DIR/negotiate-create-1.json"
NEGOTIATE_CREATE_3_PAYLOAD="$TMP_DIR/negotiate-create-3.json"
NEGOTIATE_CREATE_1_BODY="$TMP_DIR/negotiate-create-1-body.json"
NEGOTIATE_CREATE_3_BODY="$TMP_DIR/negotiate-create-3-body.json"

build_negotiate_payload "$REQUESTER_NODE1_ID" "$NEGOTIATE_CREATE_1_PAYLOAD"
build_negotiate_payload "$REQUESTER_NODE3_ID" "$NEGOTIATE_CREATE_3_PAYLOAD"

echo "==> Step 2/8: create two pending negotiations (node1 and node3 -> node2)"
NEGOTIATE_CREATE_1_CODE=$(signed_json_call 1 POST "http://localhost:8082" "/negotiate" "$NEGOTIATE_CREATE_1_PAYLOAD" "$NEGOTIATE_CREATE_1_BODY")
[[ "$NEGOTIATE_CREATE_1_CODE" == "200" ]] || {
  echo "ERROR: negotiation create #1 failed (HTTP $NEGOTIATE_CREATE_1_CODE)"
  echo "Body: $(/bin/cat "$NEGOTIATE_CREATE_1_BODY")"
  exit 1
}
assert_json "data.get('agreementId') not in (None, '') and data.get('status') == 'PENDING'" "$NEGOTIATE_CREATE_1_BODY" "negotiation #1 created in PENDING"

NEGOTIATE_CREATE_3_CODE=$(signed_json_call 3 POST "http://localhost:8082" "/negotiate" "$NEGOTIATE_CREATE_3_PAYLOAD" "$NEGOTIATE_CREATE_3_BODY")
[[ "$NEGOTIATE_CREATE_3_CODE" == "200" ]] || {
  echo "ERROR: negotiation create #2 failed (HTTP $NEGOTIATE_CREATE_3_CODE)"
  echo "Body: $(/bin/cat "$NEGOTIATE_CREATE_3_BODY")"
  exit 1
}
assert_json "data.get('agreementId') not in (None, '') and data.get('status') == 'PENDING'" "$NEGOTIATE_CREATE_3_BODY" "negotiation #2 created in PENDING"

AGREEMENT_1_ID="$(extract_json_string_field "$NEGOTIATE_CREATE_1_BODY" "agreementId")"
AGREEMENT_3_ID="$(extract_json_string_field "$NEGOTIATE_CREATE_3_BODY" "agreementId")"

if [[ -z "$AGREEMENT_1_ID" || -z "$AGREEMENT_3_ID" ]]; then
  echo "ERROR: could not parse agreement ids"
  exit 1
fi

CONFIRM_1_RAW="$TMP_DIR/confirm-1-raw.txt"
CONFIRM_3_RAW="$TMP_DIR/confirm-3-raw.txt"
CONFIRM_1_BODY="$TMP_DIR/confirm-1-body.json"
CONFIRM_3_BODY="$TMP_DIR/confirm-3-body.json"

echo "==> Step 3/8: run both confirm calls concurrently"
(
  "$DOCKER_DIR/scripts/signed-request.sh" 1 POST "http://localhost:8082" "/negotiate/${AGREEMENT_1_ID}/confirm" "$NEGOTIATE_CONFIRM_PAYLOAD" > "$CONFIRM_1_RAW" 2>&1 || true
) &
PID_CONFIRM_1=$!
(
  "$DOCKER_DIR/scripts/signed-request.sh" 3 POST "http://localhost:8082" "/negotiate/${AGREEMENT_3_ID}/confirm" "$NEGOTIATE_CONFIRM_PAYLOAD" > "$CONFIRM_3_RAW" 2>&1 || true
) &
PID_CONFIRM_3=$!

wait "$PID_CONFIRM_1"
wait "$PID_CONFIRM_3"

CONFIRM_1_CODE="$(split_raw_response "$CONFIRM_1_RAW" "$CONFIRM_1_BODY")"
CONFIRM_3_CODE="$(split_raw_response "$CONFIRM_3_RAW" "$CONFIRM_3_BODY")"

echo "==> confirm#1 HTTP: ${CONFIRM_1_CODE:-none}"
echo "==> confirm#2 HTTP: ${CONFIRM_3_CODE:-none}"

SUCCESS_COUNT=0
if [[ "$CONFIRM_1_CODE" == "200" ]]; then
  SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
fi
if [[ "$CONFIRM_3_CODE" == "200" ]]; then
  SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
fi

if [[ "$SUCCESS_COUNT" -ne 1 ]]; then
  echo "ERROR: expected exactly one successful confirm, got $SUCCESS_COUNT"
  echo "confirm#1 body: $(/bin/cat "$CONFIRM_1_BODY")"
  echo "confirm#2 body: $(/bin/cat "$CONFIRM_3_BODY")"
  exit 1
fi

if [[ "$CONFIRM_1_CODE" != "200" && "$CONFIRM_3_CODE" != "200" ]]; then
  echo "ERROR: both confirm requests failed"
  exit 1
fi

LOSER_AGREEMENT_ID=""
LOSER_SIGNER_INDEX=""
WINNER_AGREEMENT_ID=""

if [[ "$CONFIRM_1_CODE" == "200" ]]; then
  assert_json "data.get('agreementId') == '$AGREEMENT_1_ID' and data.get('status') == 'CONFIRMED'" "$CONFIRM_1_BODY" "agreement #1 confirmed"
  echo "==> agreement #2 expected to fail due to capacity contention"
  WINNER_AGREEMENT_ID="$AGREEMENT_1_ID"
  LOSER_AGREEMENT_ID="$AGREEMENT_3_ID"
  LOSER_SIGNER_INDEX="3"
else
  assert_json "data.get('agreementId') == '$AGREEMENT_3_ID' and data.get('status') == 'CONFIRMED'" "$CONFIRM_3_BODY" "agreement #2 confirmed"
  echo "==> agreement #1 expected to fail due to capacity contention"
  WINNER_AGREEMENT_ID="$AGREEMENT_3_ID"
  LOSER_AGREEMENT_ID="$AGREEMENT_1_ID"
  LOSER_SIGNER_INDEX="1"
fi

RETRY_LOSER_BODY="$TMP_DIR/retry-loser-confirm-body.json"
RETRY_LOSER_CODE=$(signed_json_call "$LOSER_SIGNER_INDEX" POST "http://localhost:8082" "/negotiate/${LOSER_AGREEMENT_ID}/confirm" "$NEGOTIATE_CONFIRM_PAYLOAD" "$RETRY_LOSER_BODY")
RETRY_SIGNATURE_GUARD_SEEN="false"

echo "==> Step 4/8: retry loser confirm with fresh signature (must still fail)"
echo "==> loser retry HTTP: ${RETRY_LOSER_CODE:-none}"
if [[ "$RETRY_LOSER_CODE" == "200" ]]; then
  echo "ERROR: loser agreement unexpectedly confirmed on retry"
  echo "Body: $(/bin/cat "$RETRY_LOSER_BODY")"
  exit 1
fi

if /usr/bin/grep -Eqi "Replay attack detected|Missing signature headers|Invalid request signature|Invalid signature timestamp" "$RETRY_LOSER_BODY"; then
  RETRY_SIGNATURE_GUARD_SEEN="true"
  echo "INFO: loser retry response traversed signature guard on /error; capacity causality is validated by API + postgres checks"
fi

AGREEMENT_1_GET_BODY="$TMP_DIR/agreement-1-get-body.json"
AGREEMENT_3_GET_BODY="$TMP_DIR/agreement-3-get-body.json"
AGREEMENT_1_GET_CODE=$(signed_json_call 1 GET "http://localhost:8082" "/negotiate/${AGREEMENT_1_ID}" "" "$AGREEMENT_1_GET_BODY")
AGREEMENT_3_GET_CODE=$(signed_json_call 3 GET "http://localhost:8082" "/negotiate/${AGREEMENT_3_ID}" "" "$AGREEMENT_3_GET_BODY")

echo "==> Step 5/8: verify agreement states through API"
[[ "$AGREEMENT_1_GET_CODE" == "200" ]] || {
  echo "ERROR: agreement #1 GET failed (HTTP $AGREEMENT_1_GET_CODE)"
  echo "Body: $(/bin/cat "$AGREEMENT_1_GET_BODY")"
  exit 1
}
[[ "$AGREEMENT_3_GET_CODE" == "200" ]] || {
  echo "ERROR: agreement #2 GET failed (HTTP $AGREEMENT_3_GET_CODE)"
  echo "Body: $(/bin/cat "$AGREEMENT_3_GET_BODY")"
  exit 1
}

if [[ "$WINNER_AGREEMENT_ID" == "$AGREEMENT_1_ID" ]]; then
  assert_json "data.get('agreementId') == '$AGREEMENT_1_ID' and data.get('status') == 'CONFIRMED'" "$AGREEMENT_1_GET_BODY" "agreement #1 is CONFIRMED via API"
  assert_json "data.get('agreementId') == '$AGREEMENT_3_ID' and data.get('status') == 'PENDING'" "$AGREEMENT_3_GET_BODY" "agreement #2 remains PENDING via API"
else
  assert_json "data.get('agreementId') == '$AGREEMENT_1_ID' and data.get('status') == 'PENDING'" "$AGREEMENT_1_GET_BODY" "agreement #1 remains PENDING via API"
  assert_json "data.get('agreementId') == '$AGREEMENT_3_ID' and data.get('status') == 'CONFIRMED'" "$AGREEMENT_3_GET_BODY" "agreement #2 is CONFIRMED via API"
fi

echo "==> Step 6/8: verify negotiation statuses persisted in postgres"
CONFIRMED_COUNT=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select count(*) from negotiation_agreement where agreement_id in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}') and status='CONFIRMED';" | /usr/bin/tr -d '\r\n '
)
PENDING_COUNT=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select count(*) from negotiation_agreement where agreement_id in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}') and status='PENDING';" | /usr/bin/tr -d '\r\n '
)

[[ "$CONFIRMED_COUNT" == "1" ]] || { echo "ERROR: expected 1 CONFIRMED agreement, got $CONFIRMED_COUNT"; exit 1; }
[[ "$PENDING_COUNT" == "1" ]] || { echo "ERROR: expected 1 PENDING agreement, got $PENDING_COUNT"; exit 1; }

echo "==> Step 7/8: verify durable capacity ledger state in postgres"
LEDGER_COMMITTED_COUNT=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select count(*) from capacity_reservation where reservation_key in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}') and status='COMMITTED';" | /usr/bin/tr -d '\r\n '
)
LEDGER_TOTAL_COUNT=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select count(*) from capacity_reservation where reservation_key in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}');" | /usr/bin/tr -d '\r\n '
)
COUNTER_OCCUPIED_BYTES=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select occupied_bytes from capacity_counter where id=1;" | /usr/bin/tr -d '\r\n '
)
PLANNED_CONFIRMED_BYTES=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select coalesce(sum(planned_reservation_bytes),0) from negotiation_agreement where agreement_id in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}') and status='CONFIRMED';" | /usr/bin/tr -d '\r\n '
)
PENDING_PLANNED_BYTES=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select coalesce(sum(planned_reservation_bytes),0) from negotiation_agreement where agreement_id in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}') and status='PENDING';" | /usr/bin/tr -d '\r\n '
)
LEDGER_RESERVED_COUNT=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select count(*) from capacity_reservation where reservation_key in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}') and status='RESERVED';" | /usr/bin/tr -d '\r\n '
)
COMMITTED_RESERVATION_KEY=$(
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
  "select reservation_key from capacity_reservation where reservation_key in ('${AGREEMENT_1_ID}','${AGREEMENT_3_ID}') and status='COMMITTED' limit 1;" | /usr/bin/tr -d '\r\n '
)

[[ "$LEDGER_COMMITTED_COUNT" == "1" ]] || { echo "ERROR: expected 1 COMMITTED capacity reservation, got $LEDGER_COMMITTED_COUNT"; exit 1; }
[[ "$LEDGER_TOTAL_COUNT" == "1" ]] || { echo "ERROR: expected 1 capacity reservation row, got $LEDGER_TOTAL_COUNT"; exit 1; }
[[ "$LEDGER_RESERVED_COUNT" == "0" ]] || { echo "ERROR: expected 0 RESERVED capacity reservations, got $LEDGER_RESERVED_COUNT"; exit 1; }
[[ "$COUNTER_OCCUPIED_BYTES" == "$PLANNED_CONFIRMED_BYTES" ]] || {
  echo "ERROR: occupied_bytes ($COUNTER_OCCUPIED_BYTES) != planned confirmed bytes ($PLANNED_CONFIRMED_BYTES)"
  exit 1
}
[[ "$COUNTER_OCCUPIED_BYTES" -le "$NODE2_CAPACITY_MAX_BYTES_OVERRIDE" ]] || {
  echo "ERROR: occupied_bytes ($COUNTER_OCCUPIED_BYTES) exceeds configured capacity override ($NODE2_CAPACITY_MAX_BYTES_OVERRIDE)"
  exit 1
}
[[ "$COMMITTED_RESERVATION_KEY" == "$WINNER_AGREEMENT_ID" ]] || {
  echo "ERROR: committed reservation key ($COMMITTED_RESERVATION_KEY) does not match confirmed agreement ($WINNER_AGREEMENT_ID)"
  exit 1
}

REMAINING_BYTES=$((NODE2_CAPACITY_MAX_BYTES_OVERRIDE - COUNTER_OCCUPIED_BYTES))
[[ "$PENDING_PLANNED_BYTES" -gt "$REMAINING_BYTES" ]] || {
  echo "ERROR: pending planned bytes ($PENDING_PLANNED_BYTES) should exceed remaining capacity ($REMAINING_BYTES)"
  exit 1
}

echo "==> Step 8/8: contention + durable ledger checks passed"

echo "SUCCESS: postgres capacity contention validated (multi-node concurrent confirm)"
echo "- agreement1: $AGREEMENT_1_ID"
echo "- agreement2: $AGREEMENT_3_ID"
echo "- confirms:   ${CONFIRM_1_CODE:-none}, ${CONFIRM_3_CODE:-none}"
echo "- loser-retry: ${RETRY_LOSER_CODE:-none}"
echo "- occupied:   $COUNTER_OCCUPIED_BYTES bytes"
if [[ "$RETRY_SIGNATURE_GUARD_SEEN" == "true" ]]; then
  echo "- note: replay warning observed on /error path while business state remained consistent with capacity contention"
fi
