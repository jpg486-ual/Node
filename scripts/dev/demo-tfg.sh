#!/usr/bin/env bash
# Orquesta una demo end-to-end del sistema con captura de evidencia estructurada.
#
# Reutiliza smokes existentes en scripts/dev/ y docker/scripts/ para componer
# secciones temáticas (cliente CLI, flow multi-nodo firmado, recovery, discovery
# dinámico, snapshot observabilidad), volcando outputs a:
#   docs/operations/demo-evidence/run-<timestamp>/
#
# Uso:
#   ./scripts/dev/demo-tfg.sh                    # secciones A + B + C + E + F
#   ./scripts/dev/demo-tfg.sh --with-chaos       # añade sección D (discovery chaos)
#   ./scripts/dev/demo-tfg.sh --keep-running     # mantiene docker stack al final
#   ./scripts/dev/demo-tfg.sh --fast             # --no-build + --reuse-keys
#   ./scripts/dev/demo-tfg.sh --help             # opciones completas
#
# Salidas:
#   exit 0          todas las secciones ejecutadas pasaron
#   exit !=0        alguna sección fallo (revisar logs en demo-evidence)
#   artifacts       docs/operations/demo-evidence/run-<timestamp>/

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

WITH_CHAOS="false"
KEEP_RUNNING="false"
FAST_MODE="false"
SKIP_A="false"
SKIP_B="false"
SKIP_C="false"
SKIP_E="false"
SKIP_F="false"

print_usage() {
  cat <<EOF
Usage: $0 [options]

Demo end-to-end TFG. Orquesta secciones A + B + C + E + F (default) o
A + B + C + D + E + F (con --with-chaos), capturando outputs a
docs/operations/demo-evidence/.

Options:
  --with-chaos       Añade sección D (smoke-discovery-liveness-chaos).
  --keep-running     Mantiene docker stack tras la demo (inspección Grafana/Tempo).
  --fast             Equivalente a --no-build --reuse-keys (acelera B/C/D/E).
  --skip-section-a   Omite sección A (cliente CLI completo).
  --skip-section-b   Omite sección B (multi-nodo signed flow).
  --skip-section-c   Omite sección C (recovery byte transfer).
  --skip-section-e   Omite sección E (discovery dinámico).
  --skip-section-f   Omite sección F (snapshot observability).
  --help             Muestra este texto.

Pre-requisitos:
  - bash, curl, python3, shasum (sección A — cliente CLI con USE_H2=true).
  - docker + docker compose (secciones B, C, D — multi-nodo cluster).
  - mvnw funcional (delega arranque del nodo H2 en sección A).

Limitaciones explícitas:
  - NodeClient Swift NO se incluye en la demo (cliente de referencia TFG es
    el cliente CLI shell de la sección A).
  - Cluster local single-host, NO productivo.
  - Chaos opcional (sección D) — flag explícito requerido.

Ver docs/operations/demo-tfg-walkthrough.md para narrativa de defensa ante
tribunal y outputs de muestra.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-chaos) WITH_CHAOS="true" ;;
    --keep-running) KEEP_RUNNING="true" ;;
    --fast) FAST_MODE="true" ;;
    --skip-section-a) SKIP_A="true" ;;
    --skip-section-b) SKIP_B="true" ;;
    --skip-section-c) SKIP_C="true" ;;
    --skip-section-e) SKIP_E="true" ;;
    --skip-section-f) SKIP_F="true" ;;
    --help|-h) print_usage; exit 0 ;;
    *) echo "ERROR: unknown option '$1'"; print_usage; exit 1 ;;
  esac
  shift
done

TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
RUN_DIR="$ROOT_DIR/docs/operations/demo-evidence/run-$TIMESTAMP"
mkdir -p "$RUN_DIR"

SUMMARY_FILE="$RUN_DIR/00-summary.txt"
HEADER_FILE="$RUN_DIR/00-header.txt"

declare -a SECTION_RESULTS=()

log_status() {
  local label="$1"
  local status="$2"
  local detail="$3"
  SECTION_RESULTS+=("$label | $status | $detail")
  echo "[demo-tfg] $label: $status — $detail"
}

write_header() {
  {
    echo "==================================================================="
    echo " Demo end-to-end TFG — Node distributed storage"
    echo "==================================================================="
    echo "Timestamp UTC : $TIMESTAMP"
    echo "Run dir       : $RUN_DIR"
    echo "With chaos    : $WITH_CHAOS"
    echo "Keep running  : $KEEP_RUNNING"
    echo "Fast mode     : $FAST_MODE"
    echo "Skip A/B/C/E/F: $SKIP_A / $SKIP_B / $SKIP_C / $SKIP_E / $SKIP_F"
    echo "Git HEAD      : $(git rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
    echo "Java version  : $(java -version 2>&1 | head -1)"
    echo "Docker avail  : $(command -v docker >/dev/null 2>&1 && echo 'yes' || echo 'no')"
    echo "==================================================================="
  } | tee "$HEADER_FILE"
}

run_section_a() {
  if [[ "$SKIP_A" == "true" ]]; then
    log_status "A (cliente CLI)" "SKIPPED" "--skip-section-a"
    return 0
  fi
  # Por defecto la sección A arranca un Spring Boot standalone con H2 en 8081
  # (`NODE_PORT` default del smoke). Si el puerto está ocupado por el cluster
  # docker (típico cuando el operador pre-arranca con --profile observability
  # antes de demo-tfg.sh), redirigimos automáticamente a un puerto alto libre
  # — la sección A sigue corriendo independientemente del cluster.
  #
  # `start-client-test-node.sh` usa un puerto interno fijo (18081) para emitir
  # el código de registro durante el bootstrap; mantenemos el server final en
  # 18091 para no colisionar con ese paso.
  local section_a_port="8081"
  local port_note=""
  if command -v lsof >/dev/null 2>&1 && lsof -i :8081 -sTCP:LISTEN >/dev/null 2>&1; then
    section_a_port="18091"
    port_note=" (puerto 8081 ocupado → A en :$section_a_port)"
  fi
  echo
  echo ">>> Sección A: Cliente CLI completo — login → sync → upload → download → logout$port_note"
  local log_file="$RUN_DIR/01-section-a-cliente-cli.log"
  local section_a_status="PASS"
  if USE_H2=true \
       NODE_PORT="$section_a_port" \
       NODE_BASE_URL="http://localhost:$section_a_port" \
       "$ROOT_DIR/scripts/dev/smoke-client-login-sync-upload-download-logout.sh" 2>&1 | tee "$log_file"; then
    section_a_status="PASS"
  else
    section_a_status="FAIL"
  fi

  # Cleanup del nodo Spring Boot standalone arrancado por start-client-test-node.sh
  # con `nohup ... &`. Sin esta limpieza el proceso sobrevive a la seccion A y
  # choca con el cluster Docker que la seccion B intenta levantar en el mismo
  # puerto (Error: ports are not available, address already in use).
  if command -v lsof >/dev/null 2>&1; then
    local stale_pids
    stale_pids=$(lsof -ti :"$section_a_port" -sTCP:LISTEN 2>/dev/null || true)
    if [[ -n "$stale_pids" ]]; then
      echo "==> Stopping section A standalone node (pids: $stale_pids, port: $section_a_port)"
      # shellcheck disable=SC2086
      kill $stale_pids 2>/dev/null || true
      sleep 2
      stale_pids=$(lsof -ti :"$section_a_port" -sTCP:LISTEN 2>/dev/null || true)
      if [[ -n "$stale_pids" ]]; then
        # shellcheck disable=SC2086
        kill -9 $stale_pids 2>/dev/null || true
        sleep 1
      fi
    fi
  fi

  if [[ "$section_a_status" == "PASS" ]]; then
    log_status "A (cliente CLI)" "PASS" "$log_file (port=$section_a_port)"
    return 0
  else
    log_status "A (cliente CLI)" "FAIL" "$log_file (port=$section_a_port)"
    return 1
  fi
}

run_section_b() {
  if [[ "$SKIP_B" == "true" ]]; then
    log_status "B (multi-nodo signed)" "SKIPPED" "--skip-section-b"
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    log_status "B (multi-nodo signed)" "SKIPPED" "docker no disponible"
    return 0
  fi
  if ! docker info >/dev/null 2>&1; then
    log_status "B (multi-nodo signed)" "SKIPPED" "docker daemon inactivo"
    return 0
  fi
  echo
  echo ">>> Sección B: Multi-nodo signed flow — discovery + negotiation + recovery custody"
  local log_file="$RUN_DIR/02-section-b-multinode-signed.log"
  local args=("--keep-running")
  if [[ "$FAST_MODE" == "true" ]]; then args+=("--no-build" "--reuse-keys"); fi
  if "$ROOT_DIR/docker/scripts/smoke-e2e.sh" "${args[@]}" 2>&1 | tee "$log_file"; then
    log_status "B (multi-nodo signed)" "PASS" "$log_file"
    return 0
  else
    log_status "B (multi-nodo signed)" "FAIL" "$log_file"
    return 1
  fi
}

run_section_c() {
  if [[ "$SKIP_C" == "true" ]]; then
    log_status "C (recovery byte transfer)" "SKIPPED" "--skip-section-c"
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    log_status "C (recovery byte transfer)" "SKIPPED" "docker no disponible"
    return 0
  fi
  echo
  echo ">>> Sección C: Recovery byte transfer real — store + download + verify bytes"
  local log_file="$RUN_DIR/03-section-c-recovery-bytes.log"
  local args=("--keep-running" "--no-build" "--reuse-keys")
  if "$ROOT_DIR/docker/scripts/smoke-recovery-bytes.sh" "${args[@]}" 2>&1 | tee "$log_file"; then
    log_status "C (recovery byte transfer)" "PASS" "$log_file"
    return 0
  else
    log_status "C (recovery byte transfer)" "FAIL" "$log_file"
    return 1
  fi
}

run_section_d() {
  if [[ "$WITH_CHAOS" != "true" ]]; then
    log_status "D (chaos engineering)" "SKIPPED" "flag --with-chaos no presente"
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    log_status "D (chaos engineering)" "SKIPPED" "docker no disponible"
    return 0
  fi
  echo
  echo ">>> Sección D: Discovery + liveness bajo chaos engineering"
  local log_file="$RUN_DIR/04-section-d-chaos.log"
  local args=("--keep-running" "--no-build")
  if "$ROOT_DIR/docker/scripts/smoke-discovery-liveness-chaos.sh" "${args[@]}" 2>&1 | tee "$log_file"; then
    log_status "D (chaos engineering)" "PASS" "$log_file"
    return 0
  else
    log_status "D (chaos engineering)" "FAIL" "$log_file"
    return 1
  fi
}

run_section_e() {
  if [[ "$SKIP_E" == "true" ]]; then
    log_status "E (discovery dinamico)" "SKIPPED" "--skip-section-e"
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    log_status "E (discovery dinamico)" "SKIPPED" "docker no disponible"
    return 0
  fi
  echo
  echo ">>> Sección E: Discovery dinámico — 4 escenarios (1-fragment-per-node + cleanup + renewal + insufficient)"
  local log_file="$RUN_DIR/section-e.log"
  # --no-build siempre (cluster ya construido por A/B/C). --keep-running para no
  # destruir el stack que las secciones siguientes necesitan vivo.
  local args=("--keep-running" "--no-build")
  if "$ROOT_DIR/docker/scripts/smoke-discovery-dynamic-distribution.sh" "${args[@]}" 2>&1 | tee "$log_file"; then
    log_status "E (discovery dinamico)" "PASS" "$log_file"
    return 0
  else
    log_status "E (discovery dinamico)" "FAIL" "$log_file"
    return 1
  fi
}

run_section_f() {
  if [[ "$SKIP_F" == "true" ]]; then
    log_status "F (snapshot observability)" "SKIPPED" "--skip-section-f"
    return 0
  fi
  if ! command -v curl >/dev/null 2>&1; then
    log_status "F (snapshot observability)" "SKIPPED" "curl no disponible"
    return 0
  fi
  # Gate: si Grafana no responde a /api/health, asumimos profile observability
  # no activo y salimos con SKIPPED (no FAIL).
  if ! curl -fsS --max-time 3 "http://localhost:3000/api/health" >/dev/null 2>&1; then
    log_status "F (snapshot observability)" "SKIPPED" "Grafana no responde (profile observability no activo)"
    return 0
  fi
  echo
  echo ">>> Sección F: Snapshot observability — Prometheus/Grafana/Loki/Tempo + ops metrics dual-emit"
  local out_dir="$RUN_DIR/06-section-f-observability"
  mkdir -p "$out_dir"
  local log_file="$out_dir/section-f.log"

  {
    echo "[F] Capturing Grafana /api/health..."
    curl -fsS --max-time 5 "http://localhost:3000/api/health" \
      -o "$out_dir/grafana-health.json" || echo "  (grafana-health failed, continuing)"

    echo "[F] Capturing Prometheus targets..."
    curl -fsS --max-time 5 "http://localhost:9090/api/v1/targets" \
      -o "$out_dir/prometheus-targets.json" || echo "  (prometheus-targets failed, continuing)"

    echo "[F] Capturing Loki /ready..."
    curl -fsS --max-time 5 "http://localhost:3100/ready" \
      -o "$out_dir/loki-ready.txt" 2>&1 || echo "  (loki-ready failed, continuing)"

    echo "[F] Capturing Tempo /ready..."
    curl -fsS --max-time 5 "http://localhost:3200/ready" \
      -o "$out_dir/tempo-ready.txt" 2>&1 || echo "  (tempo-ready failed, continuing)"

    echo "[F] Capturing PromQL snapshot of canonical metrics..."
    local metrics_snapshot="$out_dir/prometheus-metrics-snapshot.txt"
    : > "$metrics_snapshot"
    local metric
    for metric in \
        "node_discovery_candidates_active" \
        "node_discovery_queue_pending" \
        "node_discovery_queue_resolved" \
        "node_discovery_queue_failed" \
        "node_delivery_operations_total" \
        "node_delivery_operations_acked_total" \
        "node_delivery_operations_retry_total" \
        "node_delivery_operations_deadletter_total" \
        "node_custody_liveness_outbound_success_total" \
        "node_custody_liveness_outbound_failure_total" \
        "node_custody_liveness_risk_score" \
        "node_recovery_cleanup_run_total" \
        "node_recovery_cleanup_run_error_total"; do
      echo "## $metric" >> "$metrics_snapshot"
      curl -fsS --max-time 5 -G --data-urlencode "query=$metric" \
        "http://localhost:9090/api/v1/query" >> "$metrics_snapshot" 2>&1 \
        || echo "  (query for $metric failed)" >> "$metrics_snapshot"
      echo >> "$metrics_snapshot"
    done

    echo "[F] Capturing /ops/**/metrics dual-emit (3 nodos × 4 endpoints, signed)..."
    local ops_snapshot="$out_dir/ops-metrics-snapshot.json"
    local signed_helper="$ROOT_DIR/docker/scripts/signed-request.sh"
    {
      echo "{"
      echo "  \"capturedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
      echo "  \"nodes\": ["
      local node_idx
      for node_idx in 1 2 3; do
        local port=$((8080 + node_idx))
        local trailing_comma=","
        [[ "$node_idx" == "3" ]] && trailing_comma=""
        echo "    {"
        echo "      \"node\": \"node$node_idx\","
        echo "      \"port\": $port,"
        local endpoint_keys=("discovery" "delivery" "custody-liveness" "recovery")
        local endpoint_paths=("/ops/discovery/metrics" "/ops/negotiate/delivery/metrics" "/ops/custody-liveness/metrics" "/ops/recovery/consistency/metrics")
        local i
        for i in 0 1 2 3; do
          local key="${endpoint_keys[$i]}"
          local path="${endpoint_paths[$i]}"
          local sep=","
          [[ "$i" == "3" ]] && sep=""
          # /ops/** require ECDSA-signed inter-node call. Reuse the test helper that
          # signs with node{idx}'s private key and submits to its own /ops endpoint.
          local body
          body="$("$signed_helper" "$node_idx" GET "http://localhost:${port}" "$path" 2>/dev/null \
            | awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' \
            || echo 'null')"
          if ! echo "$body" | python3 -c 'import json,sys;json.load(sys.stdin)' >/dev/null 2>&1; then
            body="null"
          fi
          echo "      \"${key}\": ${body}${sep}"
        done
        echo "    }${trailing_comma}"
      done
      echo "  ]"
      echo "}"
    } > "$ops_snapshot"

    echo "[F] Snapshot completo en $out_dir"
  } 2>&1 | tee "$log_file"

  log_status "F (snapshot observability)" "PASS" "$out_dir"
  return 0
}

cleanup_docker() {
  if [[ "$KEEP_RUNNING" == "true" ]]; then
    echo "==> --keep-running: docker stack se mantiene (inspección Grafana/Tempo en localhost:3000)"
    return 0
  fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "==> docker compose down -v (cleanup post-demo)"
    (cd "$ROOT_DIR" && docker compose down -v >/dev/null 2>&1 || true)
  fi
}

write_summary() {
  {
    echo "==================================================================="
    echo " Demo TFG — RESUMEN EJECUTIVO"
    echo "==================================================================="
    echo "Timestamp UTC : $TIMESTAMP"
    echo "Run dir       : $RUN_DIR"
    echo
    echo "Resultado por sección:"
    echo "-------------------------------------------------------------------"
    printf "%-30s | %-8s | %s\n" "Sección" "Estado" "Detalle"
    echo "-------------------------------------------------------------------"
    for r in "${SECTION_RESULTS[@]}"; do
      IFS=' | ' read -r label status detail <<< "$r"
      printf "%-30s | %-8s | %s\n" "$label" "$status" "$detail"
    done
    echo "==================================================================="
  } | tee "$SUMMARY_FILE"
}

write_header

OVERALL_STATUS=0
run_section_a || OVERALL_STATUS=1
run_section_b || OVERALL_STATUS=1
run_section_c || OVERALL_STATUS=1
run_section_d || OVERALL_STATUS=1
run_section_e || OVERALL_STATUS=1
run_section_f || OVERALL_STATUS=1

cleanup_docker
write_summary

if [[ "$OVERALL_STATUS" -eq 0 ]]; then
  echo
  echo "==> Demo TFG completada PASS. Outputs capturados en: $RUN_DIR"
  exit 0
else
  echo
  echo "==> Demo TFG completada con fallos parciales. Revisar logs en: $RUN_DIR"
  exit 1
fi
