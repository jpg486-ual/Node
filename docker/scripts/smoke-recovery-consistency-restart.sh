#!/usr/bin/env bash
# Smoke del worker recovery-consistency post-restart: levanta cluster con cadencia
# determinista del worker en node2, crea divergencia metadata/payload borrando un row
# directamente en postgres-node2, reinicia node2 y verifica que la metadata huérfana
# se elimina y el control fragment sobrevive. Valida también el endpoint de métricas.
# Flags: [--keep-running] [--no-build] [--fast]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"
OVERRIDE_COMPOSE="$TMP_DIR/recovery-consistency-override.yml"

KEEP_RUNNING="false"
SKIP_BUILD="false"
DOCKER_BIN=""

cleanup() {
  local exit_code=$?

  if [[ -n "${DOCKER_BIN:-}" ]]; then
    if [[ "$KEEP_RUNNING" == "true" ]]; then
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
      cat <<EOF
Usage: $0 [--keep-running] [--no-build] [--fast]

Recovery consistency post-restart smoke flow. Los orphan fragments no llevan TTL,
así que el worker reconcilia divergencia metadata/payload sin sweep por expiry:
1) Start cluster with temporary override (deterministic consistency worker cadence on node2)
2) Store two fragments in node2: orphan-candidate, active-control
3) Delete orphan payload row directly in postgres-node2 to create metadata/payload divergence
4) Restart node2 and wait for the first post-restart maintenance cycle
5) Verify worker convergence in postgres:
   - orphan metadata removed by reconciliation
   - active control fragment remains intact
6) Validate `/ops/recovery/consistency/metrics` exposes expected counters
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

cat > "$OVERRIDE_COMPOSE" <<'YAML'
services:
  node2:
    environment:
      NODE_FEATURES_RECOVERY_ENABLED: "true"
      NODE_RECOVERY_CONSISTENCY_ENABLED: "true"
      NODE_RECOVERY_CONSISTENCY_WORKER_FIXED_DELAY_MILLIS: "15000"
      NODE_RECOVERY_CONSISTENCY_CLEANUP_BATCH_SIZE: "50"
      NODE_RECOVERY_CONSISTENCY_RECONCILIATION_BATCH_SIZE: "50"
YAML

cd "$ROOT_DIR"
if [[ "$SKIP_BUILD" == "true" ]]; then
  echo "==> Starting cluster with recovery consistency override (no build)"
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d --force-recreate
else
  echo "==> Building and starting cluster with recovery consistency override"
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
  raw_response=$(
    "$DOCKER_DIR/scripts/signed-request.sh" "$node_index" "$method" "$base_url" "$request_path" "$payload_file" || true
  )

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

signed_get_json_call() {
  local node_index="$1"
  local base_url="$2"
  local request_path="$3"
  local body_out="$4"

  local raw_response
  raw_response=$(
    "$DOCKER_DIR/scripts/signed-request.sh" "$node_index" GET "$base_url" "$request_path" || true
  )

  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$body_out"
  /usr/bin/printf "%s" "$raw_response" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}'
}

wait_for_store() {
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
    /bin/sleep 1
  done

  echo "ERROR: store failed after retries (last HTTP: ${last_code:-none})"
  if [[ -f "$body_file" ]]; then
    echo "Last response body: $(cat "$body_file")"
  fi
  return 1
}

sha256_hex() {
  local value="$1"
  /usr/bin/printf "%s" "$value" | /usr/bin/shasum -a 256 | /usr/bin/awk '{print $1}'
}

sql_scalar() {
  local sql="$1"
  local value
  value=$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -v ON_ERROR_STOP=1 -tAc "$sql")
  /usr/bin/printf "%s" "$value" | /usr/bin/tr -d '[:space:]'
}

wait_for_consistency_convergence() {
  local attempts=90
  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local orphan_meta_count
    local orphan_payload_count
    local active_meta_count
    local active_payload_count

    orphan_meta_count=$(sql_scalar "select count(*) from recovery_orphan_fragment where fragment_id='${FRAGMENT_ORPHAN}';")
    orphan_payload_count=$(sql_scalar "select count(*) from recovery_orphan_fragment_payload where fragment_id='${FRAGMENT_ORPHAN}';")
    active_meta_count=$(sql_scalar "select count(*) from recovery_orphan_fragment where fragment_id='${FRAGMENT_ACTIVE}';")
    active_payload_count=$(sql_scalar "select count(*) from recovery_orphan_fragment_payload where fragment_id='${FRAGMENT_ACTIVE}';")

    if [[ "$orphan_meta_count" == "0" && "$orphan_payload_count" == "0" &&
          "$active_meta_count" == "1" && "$active_payload_count" == "1" ]]; then
      return 0
    fi

    /bin/sleep 1
  done

  return 1
}

write_store_payload() {
  local fragment_id="$1"
  local agreement_id="$2"
  local payload_text="$3"
  local custody_seconds="$4"
  local output_file="$5"

  local payload_base64
  payload_base64=$(/usr/bin/printf "%s" "$payload_text" | /usr/bin/base64 | /usr/bin/tr -d '\n')
  local payload_checksum
  payload_checksum=$(sha256_hex "$payload_text")

  /usr/bin/jq -n \
    --arg fragmentId "$fragment_id" \
    --arg agreementId "$agreement_id" \
    --arg requesterPublicKey "$REQUESTER_PUBLIC_KEY" \
    --arg checksum "$payload_checksum" \
    --arg payloadBase64 "$payload_base64" \
    --argjson custodySeconds "$custody_seconds" \
    '{
      fragmentId: $fragmentId,
      agreementId: $agreementId,
      requesterNodeId: "node1",
      requesterPublicKey: $requesterPublicKey,
      checksumAlgorithm: "SHA-256",
      checksum: $checksum,
      payloadBase64: $payloadBase64,
      custodySeconds: $custodySeconds
    }' > "$output_file"
}

wait_for_http "http://localhost:8081" "node1"
wait_for_http "http://localhost:8082" "node2"
wait_for_http "http://localhost:8083" "node3"

FRAGMENT_ORPHAN="consistency-orphan-$(/bin/date +%s)-$RANDOM"
FRAGMENT_ACTIVE="consistency-active-$(/bin/date +%s)-$RANDOM"

ORPHAN_JSON="$TMP_DIR/orphan-store.json"
ACTIVE_JSON="$TMP_DIR/active-store.json"
STORE_BODY="$TMP_DIR/store-body.json"

write_store_payload "$FRAGMENT_ORPHAN" "consistency-orphan-agreement" "consistency-orphan-payload-$RANDOM" 600 "$ORPHAN_JSON"
write_store_payload "$FRAGMENT_ACTIVE" "consistency-active-agreement" "consistency-active-payload-$RANDOM" 600 "$ACTIVE_JSON"

echo "==> Step 1/5: store orphan-candidate and active-control fragments in node2"
wait_for_store "$ORPHAN_JSON" "$STORE_BODY"
wait_for_store "$ACTIVE_JSON" "$STORE_BODY"

echo "==> Step 2/5: create metadata/payload divergence for orphan fragment"
"$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -v ON_ERROR_STOP=1 -c \
  "delete from recovery_orphan_fragment_payload where fragment_id='${FRAGMENT_ORPHAN}';" >/dev/null

orphan_meta_before=$(sql_scalar "select count(*) from recovery_orphan_fragment where fragment_id='${FRAGMENT_ORPHAN}';")
orphan_payload_before=$(sql_scalar "select count(*) from recovery_orphan_fragment_payload where fragment_id='${FRAGMENT_ORPHAN}';")
active_meta_before=$(sql_scalar "select count(*) from recovery_orphan_fragment where fragment_id='${FRAGMENT_ACTIVE}';")
active_payload_before=$(sql_scalar "select count(*) from recovery_orphan_fragment_payload where fragment_id='${FRAGMENT_ACTIVE}';")

[[ "$orphan_meta_before" == "1" ]] || { echo "ERROR: orphan metadata should exist before restart"; exit 1; }
[[ "$orphan_payload_before" == "0" ]] || { echo "ERROR: orphan payload should be deleted before restart"; exit 1; }
[[ "$active_meta_before" == "1" && "$active_payload_before" == "1" ]] || {
  echo "ERROR: active control fragment baseline mismatch"
  exit 1
}

echo "==> Step 3/5: restart node2"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" restart node2 >/dev/null
wait_for_http "http://localhost:8082" "node2 (restarted)"

echo "==> Step 4/5: wait for consistency convergence and validate postgres state"
if ! wait_for_consistency_convergence; then
  echo "ERROR: consistency convergence did not complete in time"
  echo "Snapshot:"
  echo "  orphan metadata:  $(sql_scalar "select count(*) from recovery_orphan_fragment where fragment_id='${FRAGMENT_ORPHAN}';")"
  echo "  orphan payload:   $(sql_scalar "select count(*) from recovery_orphan_fragment_payload where fragment_id='${FRAGMENT_ORPHAN}';")"
  echo "  active metadata:  $(sql_scalar "select count(*) from recovery_orphan_fragment where fragment_id='${FRAGMENT_ACTIVE}';")"
  echo "  active payload:   $(sql_scalar "select count(*) from recovery_orphan_fragment_payload where fragment_id='${FRAGMENT_ACTIVE}';")"
  exit 1
fi

echo "==> Step 5/5: validate recovery consistency metrics endpoint"
METRICS_BODY="$TMP_DIR/recovery-consistency-metrics.json"
METRICS_CODE=$(signed_get_json_call 1 "http://localhost:8082" "/ops/recovery/consistency/metrics" "$METRICS_BODY")
METRICS_CODE=$(/usr/bin/printf "%s" "$METRICS_CODE" | /usr/bin/tr -d '\r\n')
[[ "$METRICS_CODE" == "200" ]] || {
  echo "ERROR: metrics endpoint failed HTTP $METRICS_CODE"
  echo "Body: $(cat "$METRICS_BODY")"
  exit 1
}

# El contador `recovery.cleanup.expired.deleted.total` no aplica (orphan fragments sin TTL).
# El smoke sólo valida los contadores supervivientes.
HAS_KEYS=$(/usr/bin/jq -r '
  (.metrics | has("recovery.consistency.compensation.total")) and
  (.metrics | has("recovery.consistency.reconciliation.total")) and
  (.metrics | has("recovery.cleanup.run.total")) and
  (.metrics | has("recovery.cleanup.run.error.total"))
' "$METRICS_BODY")
[[ "$HAS_KEYS" == "true" ]] || {
  echo "ERROR: recovery consistency metrics keys missing"
  echo "Body: $(cat "$METRICS_BODY")"
  exit 1
}

CLEANUP_RUN_TOTAL=$(/usr/bin/jq -r '.metrics["recovery.cleanup.run.total"] // 0' "$METRICS_BODY")
RECONCILIATION_TOTAL=$(/usr/bin/jq -r '.metrics["recovery.consistency.reconciliation.total"] // 0' "$METRICS_BODY")

[[ "$CLEANUP_RUN_TOTAL" =~ ^[0-9]+$ && "$CLEANUP_RUN_TOTAL" -ge 1 ]] || {
  echo "ERROR: recovery.cleanup.run.total should be >= 1 (actual: $CLEANUP_RUN_TOTAL)"
  echo "Body: $(cat "$METRICS_BODY")"
  exit 1
}
[[ "$RECONCILIATION_TOTAL" =~ ^[0-9]+$ && "$RECONCILIATION_TOTAL" -ge 1 ]] || {
  echo "ERROR: recovery.consistency.reconciliation.total should be >= 1 (actual: $RECONCILIATION_TOTAL)"
  echo "Body: $(cat "$METRICS_BODY")"
  exit 1
}

echo "SUCCESS: recovery consistency post-restart validated (orphan reconciliation + active preserved)"
