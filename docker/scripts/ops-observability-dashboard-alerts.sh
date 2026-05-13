#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
OBS_DIR="$DOCKER_DIR/observability"

RULES_FILE="$OBS_DIR/alert-rules.json"
PANEL_CATALOG_FILE="$OBS_DIR/panel-catalog.json"

NODE_INDEX="3"
BASE_URL=""
AUTH_TOKEN=""
AUTH_USERNAME=""
AUTH_PASSWORD=""
OUTPUT_FILE=""
FAIL_ON_WARN="false"

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
require_command python3

usage() {
  cat <<'EOF'
Usage: docker/scripts/ops-observability-dashboard-alerts.sh [options]

Genera snapshot de paneles operativos y evalua reglas iniciales de alerta
sobre endpoints /ops autenticados (firma inter-nodo + Bearer NODE_ADMIN).

Options:
  --node-index <1|2|3>        Indice de nodo local para firma (default: 3)
  --base-url <url>            URL base objetivo (default por node-index: 8081/8082/8083)
  --token <bearer>            Token Bearer ya emitido por /auth/login
  --username <user>           Usuario para obtener token automaticamente
  --password <pass>           Password para obtener token automaticamente
  --output <file>             Archivo JSON de salida (default: logs/ops-observability/...)
  --fail-on-warn              Devuelve codigo WARN cuando existan alertas warn
  --help                      Muestra esta ayuda

Exit codes:
  0   OK
  10  WARN (solo si se usa --fail-on-warn)
  20  CRITICAL

Example:
  docker/scripts/ops-observability-dashboard-alerts.sh \
    --node-index 3 \
    --base-url http://localhost:8083 \
    --username node-admin \
    --password 'Passw0rd!' \
    --fail-on-warn
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
    --fail-on-warn)
      FAIL_ON_WARN="true"
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

if [[ ! -f "$RULES_FILE" ]]; then
  echo "ERROR: rules file not found: $RULES_FILE"
  exit 1
fi
if [[ ! -f "$PANEL_CATALOG_FILE" ]]; then
  echo "ERROR: panel catalog file not found: $PANEL_CATALOG_FILE"
  exit 1
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
  OUTPUT_DIR="$ROOT_DIR/logs/ops-observability"
  /bin/mkdir -p "$OUTPUT_DIR"
  OUTPUT_FILE="$OUTPUT_DIR/ops-observability-snapshot-$(/bin/date +%Y%m%d-%H%M%S).json"
else
  /bin/mkdir -p "$(/usr/bin/dirname "$OUTPUT_FILE")"
fi

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
  'float': float,
  'len': len,
  'isinstance': isinstance,
  'list': list,
  'dict': dict,
  'str': str,
  'max': max,
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
    -H "X-Request-Id: ops-observability-$(/bin/date +%s)-$RANDOM"
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

  AUTH_TOKEN="$(json_read "$login_body" "data.get('token', '')")"
  AUTH_TOKEN="$(/usr/bin/printf "%s" "$AUTH_TOKEN" | /usr/bin/tr -d '\r\n')"
  if [[ -z "$AUTH_TOKEN" ]]; then
    echo "ERROR: /auth/login response missing token"
    echo "Body: $(/bin/cat "$login_body")"
    return 1
  fi
}

resolve_token

DISCOVERY_BODY="$TMP_DIR/discovery-metrics.json"
DELIVERY_BODY="$TMP_DIR/delivery-metrics.json"
LIVENESS_BODY="$TMP_DIR/liveness-metrics.json"
RECOVERY_BODY="$TMP_DIR/recovery-metrics.json"

DISCOVERY_CODE="$(signed_get_call "/ops/discovery/metrics" "$DISCOVERY_BODY")"
DELIVERY_CODE="$(signed_get_call "/ops/negotiate/delivery/metrics" "$DELIVERY_BODY")"
LIVENESS_CODE="$(signed_get_call "/ops/custody-liveness/metrics" "$LIVENESS_BODY")"
RECOVERY_CODE="$(signed_get_call "/ops/recovery/consistency/metrics" "$RECOVERY_BODY")"

for pair in \
  "discovery:$DISCOVERY_CODE:$DISCOVERY_BODY" \
  "delivery:$DELIVERY_CODE:$DELIVERY_BODY" \
  "liveness:$LIVENESS_CODE:$LIVENESS_BODY" \
  "recovery:$RECOVERY_CODE:$RECOVERY_BODY"; do
  IFS=':' read -r name code body <<< "$pair"
  code="$(/usr/bin/printf "%s" "$code" | /usr/bin/tr -d '\r\n')"
  if [[ "$code" != "200" ]]; then
    echo "ERROR: failed to read /ops $name metrics (HTTP $code)"
    echo "Body: $(/bin/cat "$body")"
    exit 1
  fi
done

NOW_ISO="$(/bin/date -u +"%Y-%m-%dT%H:%M:%SZ")"

/usr/bin/python3 - \
  "$RULES_FILE" \
  "$PANEL_CATALOG_FILE" \
  "$DISCOVERY_BODY" \
  "$DELIVERY_BODY" \
  "$LIVENESS_BODY" \
  "$RECOVERY_BODY" \
  "$NOW_ISO" \
  "$BASE_URL" \
  "$NODE_INDEX" \
  "$OUTPUT_FILE" <<'PY'
import json
import sys

rules_path, panel_catalog_path, discovery_path, delivery_path, liveness_path, recovery_path, now_iso, base_url, node_index, output_path = sys.argv[1:]

with open(rules_path, "r", encoding="utf-8") as fh:
    rules = json.load(fh)
with open(panel_catalog_path, "r", encoding="utf-8") as fh:
    panel_catalog = json.load(fh)
with open(discovery_path, "r", encoding="utf-8") as fh:
    discovery = json.load(fh)
with open(delivery_path, "r", encoding="utf-8") as fh:
    delivery = json.load(fh)
with open(liveness_path, "r", encoding="utf-8") as fh:
    liveness = json.load(fh)
with open(recovery_path, "r", encoding="utf-8") as fh:
    recovery = json.load(fh)

alerts = []

def add_alert(rule_id: str, severity: str, message: str, value=None, threshold=None):
    alerts.append({
        "ruleId": rule_id,
        "severity": severity,
        "message": message,
        "value": value,
        "threshold": threshold,
    })

discovery_metrics = discovery.get("metrics", {})
delivery_metrics = delivery.get("metrics", {})
liveness_metrics = liveness.get("metrics", {})
liveness_signals = liveness.get("signals", {})
recovery_metrics = recovery.get("metrics", {})

pending_count = int(discovery_metrics.get("discovery.queue.pending.count", 0))
failed_count = int(discovery_metrics.get("discovery.queue.failed.count", 0))

discovery_rules = rules["rules"]["discovery"]
if pending_count > int(discovery_rules["pendingQueueCritical"]):
    add_alert("discovery.pending.queue", "critical", "Cola pending de discovery por encima de umbral critico", pending_count, discovery_rules["pendingQueueCritical"])
elif pending_count > int(discovery_rules["pendingQueueWarn"]):
    add_alert("discovery.pending.queue", "warn", "Cola pending de discovery por encima de umbral warn", pending_count, discovery_rules["pendingQueueWarn"])

if failed_count >= int(discovery_rules["failedQueueCritical"]):
    add_alert("discovery.failed.queue", "critical", "Fallo sostenido en cola discovery", failed_count, discovery_rules["failedQueueCritical"])
elif failed_count >= int(discovery_rules["failedQueueWarn"]):
    add_alert("discovery.failed.queue", "warn", "Fallo detectado en cola discovery", failed_count, discovery_rules["failedQueueWarn"])

acked_total = float(delivery_metrics.get("delivery.operations.acked.total", 0))
deadletter_total = float(delivery_metrics.get("delivery.operations.deadletter.total", 0))
delivery_denominator = acked_total + deadletter_total
success_ratio = 1.0 if delivery_denominator <= 0 else (acked_total / delivery_denominator)
ack_latency_ms = float(delivery_metrics.get("delivery.ack.latency.ms", 0.0))

delivery_rules = rules["rules"]["delivery"]
if success_ratio < float(delivery_rules["successRatioCritical"]):
    add_alert("delivery.success.ratio", "critical", "Tasa de ACK durable por debajo de umbral critico", success_ratio, delivery_rules["successRatioCritical"])
elif success_ratio < float(delivery_rules["successRatioWarn"]):
    add_alert("delivery.success.ratio", "warn", "Tasa de ACK durable por debajo de umbral warn", success_ratio, delivery_rules["successRatioWarn"])

if ack_latency_ms > float(delivery_rules["ackLatencyMsCritical"]):
    add_alert("delivery.ack.latency", "critical", "Latencia ACK por encima de umbral critico", ack_latency_ms, delivery_rules["ackLatencyMsCritical"])
elif ack_latency_ms > float(delivery_rules["ackLatencyMsWarn"]):
    add_alert("delivery.ack.latency", "warn", "Latencia ACK por encima de umbral warn", ack_latency_ms, delivery_rules["ackLatencyMsWarn"])

liveness_rules = rules["rules"]["liveness"]
risk_level = str(liveness_signals.get("risk.current.level", "UNKNOWN"))
consensus_status = str(liveness_signals.get("consensus.current.status", "UNKNOWN"))

if risk_level in set(liveness_rules.get("riskLevelCritical", [])):
    add_alert("liveness.risk.level", "critical", "Riesgo de liveness en nivel critico", risk_level, liveness_rules.get("riskLevelCritical", []))
elif risk_level in set(liveness_rules.get("riskLevelWarn", [])):
    add_alert("liveness.risk.level", "warn", "Riesgo de liveness en nivel warn", risk_level, liveness_rules.get("riskLevelWarn", []))

if consensus_status in set(liveness_rules.get("consensusStatusCritical", [])):
    add_alert("liveness.consensus.status", "critical", "Estado de consenso en nivel critico", consensus_status, liveness_rules.get("consensusStatusCritical", []))
elif consensus_status in set(liveness_rules.get("consensusStatusWarn", [])):
    add_alert("liveness.consensus.status", "warn", "Estado de consenso en nivel warn", consensus_status, liveness_rules.get("consensusStatusWarn", []))

cleanup_total = float(recovery_metrics.get("recovery.cleanup.run.total", 0))
cleanup_error = float(recovery_metrics.get("recovery.cleanup.run.error.total", 0))
cleanup_ratio = 0.0 if cleanup_total <= 0 else (cleanup_error / cleanup_total)

recovery_rules = rules["rules"]["recovery"]
if cleanup_ratio > float(recovery_rules["cleanupErrorRatioCritical"]):
    add_alert("recovery.cleanup.error.ratio", "critical", "Error ratio de cleanup por encima de umbral critico", cleanup_ratio, recovery_rules["cleanupErrorRatioCritical"])
elif cleanup_ratio > float(recovery_rules["cleanupErrorRatioWarn"]):
    add_alert("recovery.cleanup.error.ratio", "warn", "Error ratio de cleanup por encima de umbral warn", cleanup_ratio, recovery_rules["cleanupErrorRatioWarn"])

severity_order = {"ok": 0, "warn": 1, "critical": 2}
max_severity = "ok"
for alert in alerts:
    if severity_order[alert["severity"]] > severity_order[max_severity]:
        max_severity = alert["severity"]

snapshot = {
    "generatedAt": now_iso,
    "target": {
        "baseUrl": base_url,
        "nodeIndex": node_index,
    },
    "catalogVersion": panel_catalog.get("version"),
    "rulesVersion": rules.get("version"),
    "summary": {
        "maxSeverity": max_severity,
        "alertCount": len(alerts),
    },
    "derived": {
        "deliveryAckSuccessRatio": success_ratio,
        "deliveryAckLatencyMs": ack_latency_ms,
        "recoveryCleanupErrorRatio": cleanup_ratio,
        "livenessConsensusStatus": consensus_status,
        "livenessRiskLevel": risk_level,
    },
    "panels": {
        "discovery": discovery,
        "delivery": delivery,
        "liveness": liveness,
        "recovery": recovery,
    },
    "alerts": alerts,
}

with open(output_path, "w", encoding="utf-8") as fh:
    json.dump(snapshot, fh, indent=2, ensure_ascii=False)

print(json.dumps(snapshot["summary"], ensure_ascii=False))
PY

MAX_SEVERITY="$(json_read "$OUTPUT_FILE" "data.get('summary', {}).get('maxSeverity', 'ok')")"
ALERT_COUNT="$(json_read "$OUTPUT_FILE" "int(data.get('summary', {}).get('alertCount', 0))")"

echo "==> observability snapshot generated"
echo "target: $BASE_URL (node-index=$NODE_INDEX)"
echo "snapshot: $OUTPUT_FILE"
echo "maxSeverity: $MAX_SEVERITY"
echo "alertCount: $ALERT_COUNT"

echo "==> Panel quick view"
echo "discovery.pending: $(json_read "$OUTPUT_FILE" "int(data.get('panels', {}).get('discovery', {}).get('metrics', {}).get('discovery.queue.pending.count', 0))")"
echo "delivery.ack.success.ratio: $(json_read "$OUTPUT_FILE" "float(data.get('derived', {}).get('deliveryAckSuccessRatio', 0.0))")"
echo "delivery.ack.latency.ms: $(json_read "$OUTPUT_FILE" "float(data.get('derived', {}).get('deliveryAckLatencyMs', 0.0))")"
echo "liveness.consensus.status: $(json_read "$OUTPUT_FILE" "data.get('derived', {}).get('livenessConsensusStatus', 'UNKNOWN')")"
echo "recovery.cleanup.error.ratio: $(json_read "$OUTPUT_FILE" "float(data.get('derived', {}).get('recoveryCleanupErrorRatio', 0.0))")"

if [[ "$MAX_SEVERITY" == "critical" ]]; then
  exit 20
fi
if [[ "$MAX_SEVERITY" == "warn" && "$FAIL_ON_WARN" == "true" ]]; then
  exit 10
fi
exit 0
