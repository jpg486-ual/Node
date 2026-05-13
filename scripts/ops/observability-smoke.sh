#!/usr/bin/env bash
# Smoke del stack de observabilidad completo (Prometheus + Grafana + Loki + Tempo).
#
# Pasos:
#   1. Arranca el profile observability (no afecta al stack default si ya esta up).
#   2. Espera a Prometheus ready + Grafana /api/health.
#   3. Valida sintacticamente las alert rules (promtool check rules en contenedor).
#   4. Consulta `up == 1` para los 3 nodos en Prometheus.
#   5. Ejecuta verify-metrics-export.sh contra node1 como prueba dual-emit.
#
# Uso:
#   ./scripts/ops/observability-smoke.sh
#   ./scripts/ops/observability-smoke.sh --keep-up    # sin teardown final
#
# Salidas:
#   exit 0       stack OK
#   exit !=0     algún paso falló (revisar stderr)
#
# Requisitos: GRAFANA_ADMIN_PASSWORD definida en env (obligada por compose).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
KEEP_UP="false"

for arg in "$@"; do
  case "${arg}" in
    --keep-up) KEEP_UP="true" ;;
    *) echo "[obs-smoke] argumento desconocido: ${arg}" >&2; exit 1 ;;
  esac
done

cd "${PROJECT_ROOT}"

if [[ -z "${GRAFANA_ADMIN_PASSWORD:-}" ]]; then
  echo "[obs-smoke] ERROR: GRAFANA_ADMIN_PASSWORD no definida en el entorno." >&2
  echo "[obs-smoke] Ejemplo: GRAFANA_ADMIN_PASSWORD=localdev scripts/ops/observability-smoke.sh" >&2
  exit 1
fi

teardown() {
  if [[ "${KEEP_UP}" == "true" ]]; then
    echo "[obs-smoke] --keep-up: stack obs sigue corriendo."
    echo "[obs-smoke] Para pararlo:  docker compose --profile observability down"
    return
  fi
  echo "[obs-smoke] teardown stack obs..."
  docker compose --profile observability down --remove-orphans >/dev/null 2>&1 || true
}
trap teardown EXIT

echo "[obs-smoke] arranque profile observability..."
docker compose --profile observability up -d >/dev/null

wait_for_http() {
  local url="$1"
  local attempts="${2:-30}"
  for ((i=0; i<attempts; i++)); do
    if curl -fsS -o /dev/null "${url}"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

echo "[obs-smoke] esperando Prometheus /-/ready..."
wait_for_http "http://localhost:9090/-/ready" 45 || {
  echo "[obs-smoke] FAIL: Prometheus no alcanza /-/ready" >&2
  docker compose logs prometheus | tail -40 >&2
  exit 1
}

echo "[obs-smoke] esperando Grafana /api/health..."
wait_for_http "http://localhost:3000/api/health" 45 || {
  echo "[obs-smoke] FAIL: Grafana no alcanza /api/health" >&2
  docker compose logs grafana | tail -40 >&2
  exit 1
}

echo "[obs-smoke] esperando Tempo /ready..."
wait_for_http "http://localhost:3200/ready" 45 || {
  echo "[obs-smoke] FAIL: Tempo no alcanza /ready" >&2
  docker compose logs tempo | tail -40 >&2
  exit 1
}

echo "[obs-smoke] esperando Loki /ready..."
wait_for_http "http://localhost:3100/ready" 45 || {
  echo "[obs-smoke] FAIL: Loki no alcanza /ready" >&2
  docker compose logs loki | tail -40 >&2
  exit 1
}

echo "[obs-smoke] promtool check rules..."
docker compose exec -T prometheus \
  promtool check rules /etc/prometheus/rules/node-alerts.yml

echo "[obs-smoke] verificando 3 targets node up == 1..."
# Prometheus tarda 1-2 scrape_intervals (15s) en marcar un target como up.
# Cold start de Spring Boot 3.5 sobre Tomcat sin warm cache puede llegar a 60-90s
# antes de exponer /actuator/prometheus en :8181. Damos 150s (50 iter x 3s) de
# margen para que coincidan los 3 nodos disponibles + 1-2 scrape intervals.
for ((i=0; i<50; i++)); do
  up_count="$(curl -fsS --data-urlencode 'query=up{job="node"}==1' \
    'http://localhost:9090/api/v1/query' \
    | python3 -c 'import json, sys; d=json.load(sys.stdin); print(len(d["data"]["result"]))')"
  if [[ "${up_count}" == "3" ]]; then
    break
  fi
  sleep 3
done
if [[ "${up_count:-0}" != "3" ]]; then
  echo "[obs-smoke] FAIL: esperaba 3 targets 'node' up; observo ${up_count:-0}." >&2
  curl -fsS 'http://localhost:9090/api/v1/targets' | python3 -m json.tool | tail -60 >&2
  exit 1
fi

echo "[obs-smoke] verify-metrics-export.sh sobre node1..."
"${SCRIPT_DIR}/verify-metrics-export.sh" node1

echo "[obs-smoke] verificando que Loki recibio series de los 3 promtail sidecars..."
# Loki tarda 30-60s en aceptar el primer chunk + indexarlo. Los nodos
# escriben startup logs nada mas arrancar, asi que debe haber al menos 1
# stream cuando llegamos aqui. Threshold pragmatico: >=1 nodo distinto.
for ((i=0; i<20; i++)); do
  nodes_present="$(curl -fsS \
    'http://localhost:3100/loki/api/v1/series?match[]=%7Bjob%3D%22node%22%7D' \
    2>/dev/null \
    | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)
    streams=d.get("data",[])
    print(len({s.get("node") for s in streams if s.get("node")}))
except Exception:
    print(0)' 2>/dev/null)"
  if [[ "${nodes_present:-0}" -ge 1 ]]; then
    echo "[obs-smoke]   Loki tiene streams de ${nodes_present} nodo(s) distintos."
    break
  fi
  sleep 3
done
if [[ "${nodes_present:-0}" -lt 1 ]]; then
  echo "[obs-smoke] FAIL: Loki no recibio streams de ningun promtail sidecar." >&2
  echo "[obs-smoke] logs de los sidecars:" >&2
  docker compose logs promtail-node1 promtail-node2 promtail-node3 | tail -60 >&2
  exit 1
fi

echo "[obs-smoke] OK: Prometheus + Grafana + Tempo + Loki ready; 3 targets up; rules validas; promtail push verificado."
