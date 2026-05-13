#!/usr/bin/env bash
# Verifica que /actuator/prometheus expone las metricas canonicas esperadas.
#
# Por defecto hace scrape sobre node1 en la red docker compose via
# `docker compose exec` (no requiere publicar el puerto management).
#
# Uso:
#   scripts/ops/verify-metrics-export.sh                    # default: node1
#   scripts/ops/verify-metrics-export.sh node2              # otro servicio
#   TARGET_URL=http://localhost:8181 scripts/ops/verify-metrics-export.sh
#
# Salidas:
#   exit 0       todas las metricas canonicas aparecen en el payload OpenMetrics
#   exit !=0     falta alguna o el endpoint no responde

set -euo pipefail

NODE_NAME="${1:-node1}"
TARGET_URL="${TARGET_URL:-}"

# Subset canonico: una metrica representativa por familia. Si aparecen todas,
# la instrumentacion Micrometer esta viva. Cubre discovery, custody-liveness
# (probes inbound/outbound + transitions), recovery (cleanup + consistency +
# file-integrity).
EXPECTED_METRICS=(
  "node_discovery_queue_pending"
  "node_discovery_queue_failed"
  "node_discovery_candidates_active"
  "node_custody_liveness_outbound_scheduled_total"
  "node_custody_liveness_outbound_success_total"
  "node_custody_liveness_outbound_failure_total"
  "node_custody_liveness_inbound_total"
  "node_custody_liveness_transition_unresponsive_total"
  "node_custody_liveness_expiry_escalation_total"
  "node_recovery_cleanup_run_total"
  "node_recovery_cleanup_run_error_total"
  "node_recovery_consistency_reconciliation_total"
)

fetch_payload() {
  if [[ -n "${TARGET_URL}" ]]; then
    curl -fsS "${TARGET_URL}/actuator/prometheus"
    return
  fi
  if ! docker compose ps --services --filter "status=running" 2>/dev/null | grep -q "^${NODE_NAME}$"; then
    echo "[verify-metrics] ERROR: servicio '${NODE_NAME}' no esta corriendo en docker compose." >&2
    echo "[verify-metrics] Arranca el stack primero:  docker compose up -d" >&2
    exit 1
  fi
  # eclipse-temurin:25-jre no incluye wget/curl; resolvemos vía el contenedor
  # prometheus que sí los tiene y comparte la red docker default. Llega al
  # management port de cada nodo por su DNS docker compose.
  docker compose exec -T prometheus \
    wget -qO- "http://${NODE_NAME}:${MANAGEMENT_SERVER_PORT:-8181}/actuator/prometheus"
}

payload="$(fetch_payload)"
if [[ -z "${payload}" ]]; then
  echo "[verify-metrics] ERROR: payload vacio desde /actuator/prometheus" >&2
  exit 1
fi

missing=()
for metric in "${EXPECTED_METRICS[@]}"; do
  if ! grep -q "^${metric}[ {]" <<<"${payload}"; then
    missing+=("${metric}")
  fi
done

if ((${#missing[@]} > 0)); then
  echo "[verify-metrics] FAIL: faltan metricas en /actuator/prometheus:" >&2
  for m in "${missing[@]}"; do echo "  - ${m}" >&2; done
  exit 1
fi

echo "[verify-metrics] OK: ${#EXPECTED_METRICS[@]} metricas canonicas presentes en ${NODE_NAME}/actuator/prometheus."
