#!/usr/bin/env bash
# Smoke discovery + liveness bajo degradación: request sin candidatos → PENDING,
# inyectar candidate temporal → RESOLVED, pause node1 → escalation probe a tutor,
# unpause → recovery en BD + métricas observability.
# Flags: [--keep-running] [--no-build] [--fast]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"
OVERRIDE_COMPOSE="$TMP_DIR/discovery-liveness-chaos-override.yml"

KEEP_RUNNING="false"
SKIP_BUILD="false"
PAUSED_NODE1="false"
DOCKER_BIN=""

cleanup() {
  local exit_code=$?

  if [[ "$PAUSED_NODE1" == "true" && -n "${DOCKER_BIN:-}" ]]; then
    "$DOCKER_BIN" unpause distributed-node-1 >/dev/null 2>&1 || true
  fi

  if [[ -z "${DOCKER_BIN:-}" ]]; then
    /bin/rm -rf "$TMP_DIR"
    return "$exit_code"
  fi

  if [[ "$KEEP_RUNNING" == "true" ]]; then
    echo "==> Keeping cluster running (--keep-running)"
  else
    if [[ -f "$OVERRIDE_COMPOSE" ]]; then
      "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" down -v >/dev/null 2>&1 || true
    else
      "$DOCKER_BIN" compose down -v >/dev/null 2>&1 || true
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
      cat <<EOF
Usage: $0 [--keep-running] [--no-build] [--fast]

Discovery + liveness chaos smoke flow:
1) Start cluster with temporary override (discovery retry fast + liveness enabled on node3)
2) Trigger discovery request with empty candidate result and validate queued retry status
3) Add candidate via ops and validate retry resolves + discovery metrics snapshot
4) Simulate network loss/delay by pausing node1 and trigger liveness probe from node3
5) Validate liveness escalates and metrics capture failures/transitions
6) Restart node1 and validate probe flow recovers
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

if [[ ! -f "$KEYS_DIR/node1-private.der" || ! -f "$KEYS_DIR/node2-private.der" || ! -f "$KEYS_DIR/node3-private.der" ]]; then
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
else
  echo "==> Reusing existing node keys/env"
fi

cat > "$OVERRIDE_COMPOSE" <<'YAML'
services:
  node2:
    environment:
      NODE_DISCOVERY_RETRY_ENABLED: "true"
      NODE_DISCOVERY_RETRY_FIXED_DELAY_MILLIS: "1000"
      NODE_DISCOVERY_RETRY_BATCH_SIZE: "20"
      NODE_DISCOVERY_RETRY_BASE_DELAY_SECONDS: "1"
      NODE_DISCOVERY_RETRY_MAX_DELAY_SECONDS: "2"
      NODE_DISCOVERY_RETRY_MAX_ATTEMPTS: "0"
  node3:
    environment:
      NODE_CUSTODY_LIVENESS_ENABLED: "true"
      NODE_CUSTODY_LIVENESS_FAST_RETRY_INTERVAL_SECONDS: "1"
      NODE_CUSTODY_LIVENESS_MAX_FAST_RETRIES: "1"
      NODE_CUSTODY_LIVENESS_WORKER_FIXED_DELAY_MILLIS: "1000"
      NODE_CUSTODY_LIVENESS_REQUEST_TIMEOUT_MILLIS: "700"
      NODE_CUSTODY_LIVENESS_ESCALATION_POLICY: "KEEP_AND_ALERT"
      # NOTA: NO setear NODE_CUSTODY_LIVENESS_REMOTE_BASE_URLS_<algo> aquí — el
      # SPRING_APPLICATION_JSON del docker-compose.yml principal define el mapping
      # completo (3 hashes nodeId → URL) y tiene PRECEDENCIA sobre env vars
      # individuales en Spring Boot. Cualquier override aquí queda inerte.
      # El smoke usa $NODE1_ID (hash derivado de la pubkey) en sus URLs probe-now,
      # que sí matchea el mapping efectivo.
YAML

cd "$ROOT_DIR"
if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster with chaos override (no build)"
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d --force-recreate
else
  echo "==> Building and starting cluster with chaos override"
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

signed_call() {
  local node_index="$1"
  local method="$2"
  local base_url="$3"
  local request_path="$4"
  local payload_file="$5"
  local body_out="$6"

  local raw_response
  if [[ -n "$payload_file" ]]; then
    raw_response=$("$DOCKER_DIR/scripts/signed-request.sh" "$node_index" "$method" "$base_url" "$request_path" "$payload_file" || true)
  else
    raw_response=$("$DOCKER_DIR/scripts/signed-request.sh" "$node_index" "$method" "$base_url" "$request_path" || true)
  fi

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

json_read() {
  local body_file="$1"
  local expression="$2"
  /usr/bin/python3 - "$body_file" "$expression" <<'PY'
import json
import sys

path = sys.argv[1]
expr = sys.argv[2]
with open(path, 'r', encoding='utf-8') as fh:
    data = json.load(fh)
safe_builtins = {
  'int': int,
  'len': len,
  'isinstance': isinstance,
  'list': list,
  'dict': dict,
}
value = eval(expr, {'__builtins__': safe_builtins}, {'data': data})
if isinstance(value, bool):
    print('true' if value else 'false')
elif value is None:
    print('')
else:
    print(value)
PY
}

wait_for_http "http://localhost:8081" "node1"
wait_for_http "http://localhost:8082" "node2"
wait_for_http "http://localhost:8083" "node3"

NODE1_HASH="$(/usr/bin/shasum -a 256 "$KEYS_DIR/node1-public.der" | /usr/bin/awk '{print $1}')"
NODE1_ID="node-${NODE1_HASH:0:24}"
CHAOS_DOMAIN="chaos/site-a"
CHAOS_CANDIDATE_ID="node-chaos-temp"

DISCOVERY_REQ_JSON="$TMP_DIR/discovery-chaos-request.json"
DISCOVERY_REQ_BODY="$TMP_DIR/discovery-chaos-response.json"

/usr/bin/jq -n \
  --arg nodeId "$NODE1_ID" \
  --arg targetFailureDomain "$CHAOS_DOMAIN" \
  '{
    nodeId: $nodeId,
    failureDomain: "corp/a",
    requestedBucket: 1024,
    ratio: 1.0,
    maxCandidates: 5,
    targetFailureDomain: $targetFailureDomain
  }' > "$DISCOVERY_REQ_JSON"

echo "==> Discovery chaos: requesting candidates in domain with no active profiles"
DISCOVERY_CODE="$(signed_call 1 POST "http://localhost:8082" "/ops/discovery/query" "$DISCOVERY_REQ_JSON" "$DISCOVERY_REQ_BODY")"
DISCOVERY_CODE="$(/usr/bin/printf "%s" "$DISCOVERY_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$DISCOVERY_CODE" != "200" ]]; then
  echo "ERROR: discovery request failed (HTTP $DISCOVERY_CODE)"
  echo "Body: $(/bin/cat "$DISCOVERY_REQ_BODY")"
  exit 1
fi

CANDIDATE_COUNT="$(json_read "$DISCOVERY_REQ_BODY" "len(data.get('candidates', []))")"
QUEUED_REQUEST_ID="$(json_read "$DISCOVERY_REQ_BODY" "data.get('queuedRequestId', '')")"
if [[ "$CANDIDATE_COUNT" != "0" || -z "$QUEUED_REQUEST_ID" ]]; then
  echo "ERROR: expected empty candidates + queuedRequestId"
  echo "Body: $(/bin/cat "$DISCOVERY_REQ_BODY")"
  exit 1
fi

echo "==> Discovery queue id: $QUEUED_REQUEST_ID"
QUEUED_STATUS_BODY="$TMP_DIR/discovery-queued-status.json"
QUEUED_STATUS_CODE="$(signed_call 1 GET "http://localhost:8082" "/ops/discovery/queued/$QUEUED_REQUEST_ID" "" "$QUEUED_STATUS_BODY")"
QUEUED_STATUS_CODE="$(/usr/bin/printf "%s" "$QUEUED_STATUS_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$QUEUED_STATUS_CODE" != "200" ]]; then
  echo "ERROR: queued status lookup failed (HTTP $QUEUED_STATUS_CODE)"
  echo "Body: $(/bin/cat "$QUEUED_STATUS_BODY")"
  exit 1
fi

INITIAL_STATUS="$(json_read "$QUEUED_STATUS_BODY" "data.get('status', '')")"
if [[ "$INITIAL_STATUS" != "PENDING" ]]; then
  echo "ERROR: expected queued request to be PENDING, got '$INITIAL_STATUS'"
  echo "Body: $(/bin/cat "$QUEUED_STATUS_BODY")"
  exit 1
fi

echo "==> Injecting temporary candidate in chaos domain"
UPSERT_JSON="$TMP_DIR/discovery-candidate-upsert.json"
UPSERT_BODY="$TMP_DIR/discovery-candidate-upsert-body.json"
# baseUrl es obligatorio en el payload del upsert: el supernodo lo persiste y los
# origines lo usan para resolver discoveredNodeId -> URL custodia.
/usr/bin/jq -n \
  --arg failureDomain "$CHAOS_DOMAIN" \
  '{
    failureDomain: $failureDomain,
    baseUrl: "http://chaos-temp:8080",
    originalRequestedBucket: 1024,
    acceptedBuckets: [1024]
  }' > "$UPSERT_JSON"

UPSERT_CODE="$(signed_call 1 PUT "http://localhost:8082" "/ops/discovery/candidates/$CHAOS_CANDIDATE_ID" "$UPSERT_JSON" "$UPSERT_BODY")"
UPSERT_CODE="$(/usr/bin/printf "%s" "$UPSERT_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$UPSERT_CODE" != "200" ]]; then
  echo "ERROR: candidate upsert failed (HTTP $UPSERT_CODE)"
  echo "Body: $(/bin/cat "$UPSERT_BODY")"
  exit 1
fi

echo "==> Waiting for queued discovery request resolution"
FINAL_STATUS=""
for _ in $(/usr/bin/seq 1 45); do
  local_code="$(signed_call 1 GET "http://localhost:8082" "/ops/discovery/queued/$QUEUED_REQUEST_ID" "" "$QUEUED_STATUS_BODY")"
  local_code="$(/usr/bin/printf "%s" "$local_code" | /usr/bin/tr -d '\r\n')"
  if [[ "$local_code" == "200" ]]; then
    FINAL_STATUS="$(json_read "$QUEUED_STATUS_BODY" "data.get('status', '')")"
    if [[ "$FINAL_STATUS" == "RESOLVED" ]]; then
      break
    fi
  fi
  /bin/sleep 1
done

if [[ "$FINAL_STATUS" != "RESOLVED" ]]; then
  echo "ERROR: expected RESOLVED discovery queue status, got '$FINAL_STATUS'"
  echo "Body: $(/bin/cat "$QUEUED_STATUS_BODY")"
  exit 1
fi

DISCOVERY_METRICS_BODY="$TMP_DIR/discovery-metrics.json"
DISCOVERY_METRICS_CODE="$(signed_call 1 GET "http://localhost:8082" "/ops/discovery/metrics" "" "$DISCOVERY_METRICS_BODY")"
DISCOVERY_METRICS_CODE="$(/usr/bin/printf "%s" "$DISCOVERY_METRICS_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$DISCOVERY_METRICS_CODE" != "200" ]]; then
  echo "ERROR: discovery metrics endpoint failed (HTTP $DISCOVERY_METRICS_CODE)"
  echo "Body: $(/bin/cat "$DISCOVERY_METRICS_BODY")"
  exit 1
fi

DISCOVERY_RESOLVED_COUNT="$(json_read "$DISCOVERY_METRICS_BODY" "int(data.get('metrics', {}).get('discovery.queue.resolved.count', 0))")"
DISCOVERY_ACTIVE_CANDIDATES="$(json_read "$DISCOVERY_METRICS_BODY" "int(data.get('metrics', {}).get('discovery.candidates.active.count', 0))")"
if [[ "$DISCOVERY_RESOLVED_COUNT" -lt 1 || "$DISCOVERY_ACTIVE_CANDIDATES" -lt 1 ]]; then
  echo "ERROR: discovery metrics did not reach expected values"
  echo "Body: $(/bin/cat "$DISCOVERY_METRICS_BODY")"
  exit 1
fi

echo "==> Liveness chaos: pause node1 to simulate packet loss/delay"
"$DOCKER_BIN" pause distributed-node-1 >/dev/null
PAUSED_NODE1="true"
/bin/sleep 2

PROBE_NOW_BODY="$TMP_DIR/liveness-probe-now-body.json"
EMPTY_JSON="$TMP_DIR/empty.json"
echo '{}' > "$EMPTY_JSON"
PROBE_NOW_CODE="$(signed_call 3 POST "http://localhost:8083" "/ops/custody-liveness/remote/$NODE1_ID/probe-now" "$EMPTY_JSON" "$PROBE_NOW_BODY")"
PROBE_NOW_CODE="$(/usr/bin/printf "%s" "$PROBE_NOW_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$PROBE_NOW_CODE" != "200" ]]; then
  echo "ERROR: liveness probe-now failed (HTTP $PROBE_NOW_CODE)"
  echo "Body: $(/bin/cat "$PROBE_NOW_BODY")"
  exit 1
fi

REMOTE_SESSIONS_BODY="$TMP_DIR/liveness-remote-sessions.json"
LIVENESS_STATUS=""
for _ in $(/usr/bin/seq 1 50); do
  local_code="$(signed_call 3 GET "http://localhost:8083" "/ops/custody-liveness/remote/$NODE1_ID" "" "$REMOTE_SESSIONS_BODY")"
  local_code="$(/usr/bin/printf "%s" "$local_code" | /usr/bin/tr -d '\r\n')"
  if [[ "$local_code" == "200" ]]; then
    LIVENESS_STATUS="$(json_read "$REMOTE_SESSIONS_BODY" "data[0].get('status', '') if isinstance(data, list) and data else ''")"
    if [[ "$LIVENESS_STATUS" == "ESCALATED" ]]; then
      break
    fi
  fi
  /bin/sleep 1
done

if [[ "$LIVENESS_STATUS" != "ESCALATED" ]]; then
  echo "ERROR: expected ESCALATED liveness status after paused-node chaos, got '$LIVENESS_STATUS'"
  echo "Body: $(/bin/cat "$REMOTE_SESSIONS_BODY")"
  exit 1
fi

"$DOCKER_BIN" unpause distributed-node-1 >/dev/null
PAUSED_NODE1="false"
wait_for_http "http://localhost:8081" "node1 (unpaused)"

LIVENESS_METRICS_BODY="$TMP_DIR/liveness-metrics.json"
LIVENESS_METRICS_CODE="$(signed_call 3 GET "http://localhost:8083" "/ops/custody-liveness/metrics" "" "$LIVENESS_METRICS_BODY")"
LIVENESS_METRICS_CODE="$(/usr/bin/printf "%s" "$LIVENESS_METRICS_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$LIVENESS_METRICS_CODE" != "200" ]]; then
  echo "ERROR: liveness metrics endpoint failed (HTTP $LIVENESS_METRICS_CODE)"
  echo "Body: $(/bin/cat "$LIVENESS_METRICS_BODY")"
  exit 1
fi

OUTBOUND_FAILURES="$(json_read "$LIVENESS_METRICS_BODY" "int(data.get('metrics', {}).get('custody.liveness.outbound.failure.total', 0))")"
ESCALATED_TRANSITIONS="$(json_read "$LIVENESS_METRICS_BODY" "int(data.get('metrics', {}).get('custody.liveness.transition.escalated.total', 0))")"
if [[ "$OUTBOUND_FAILURES" -lt 1 || "$ESCALATED_TRANSITIONS" -lt 1 ]]; then
  echo "ERROR: liveness metrics did not capture expected failure/escalation"
  echo "Body: $(/bin/cat "$LIVENESS_METRICS_BODY")"
  exit 1
fi

echo "==> Restart chaos: stop/start node1"
"$DOCKER_BIN" stop distributed-node-1 >/dev/null
"$DOCKER_BIN" start distributed-node-1 >/dev/null
wait_for_http "http://localhost:8081" "node1 (restarted)"

echo "==> Triggering probe-now after restart to verify recovery"
SECOND_PROBE_BODY="$TMP_DIR/liveness-probe-now-second-body.json"
SECOND_PROBE_CODE="$(signed_call 3 POST "http://localhost:8083" "/ops/custody-liveness/remote/$NODE1_ID/probe-now" "$EMPTY_JSON" "$SECOND_PROBE_BODY")"
SECOND_PROBE_CODE="$(/usr/bin/printf "%s" "$SECOND_PROBE_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$SECOND_PROBE_CODE" != "200" ]]; then
  echo "ERROR: second probe-now after restart failed (HTTP $SECOND_PROBE_CODE)"
  echo "Body: $(/bin/cat "$SECOND_PROBE_BODY")"
  exit 1
fi

POST_RESTART_STATUS=""
for _ in $(/usr/bin/seq 1 40); do
  local_code="$(signed_call 3 GET "http://localhost:8083" "/ops/custody-liveness/remote/$NODE1_ID" "" "$REMOTE_SESSIONS_BODY")"
  local_code="$(/usr/bin/printf "%s" "$local_code" | /usr/bin/tr -d '\r\n')"
  if [[ "$local_code" == "200" ]]; then
    POST_RESTART_STATUS="$(json_read "$REMOTE_SESSIONS_BODY" "data[0].get('status', '') if isinstance(data, list) and data else ''")"
    if [[ "$POST_RESTART_STATUS" == "ACTIVE" ]]; then
      break
    fi
  fi
  /bin/sleep 1
done

if [[ "$POST_RESTART_STATUS" != "ACTIVE" ]]; then
  echo "ERROR: expected ACTIVE status after node1 restart and fresh probe, got '$POST_RESTART_STATUS'"
  echo "Body: $(/bin/cat "$REMOTE_SESSIONS_BODY")"
  exit 1
fi

echo "==> Cleaning up temporary discovery candidate"
DELETE_BODY="$TMP_DIR/discovery-candidate-delete-body.json"
DELETE_CODE="$(signed_call 1 DELETE "http://localhost:8082" "/ops/discovery/candidates/$CHAOS_CANDIDATE_ID" "" "$DELETE_BODY")"
DELETE_CODE="$(/usr/bin/printf "%s" "$DELETE_CODE" | /usr/bin/tr -d '\r\n')"
if [[ "$DELETE_CODE" != "204" ]]; then
  echo "WARNING: candidate cleanup returned HTTP $DELETE_CODE"
  echo "Body: $(/bin/cat "$DELETE_BODY")"
fi

echo "==> Discovery + liveness chaos smoke passed"
