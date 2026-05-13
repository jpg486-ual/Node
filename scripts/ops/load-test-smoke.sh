#!/usr/bin/env bash
# Mide carga HTTP del nodo con Apache Bench: RPS + latencias p50/p95/p99 sobre
# 4 endpoints representativos. Configuración por defecto: 1000 requests, 10
# concurrentes, keep-alive, max 30s por endpoint.
#
# Pre-requisitos:
#   - ab (Apache Bench): macOS /usr/sbin/ab; Linux: apt install apache2-utils
#   - bash, curl, python3
#   - Nodo arrancado en NODE_BASE_URL (default http://localhost:8081)
#   - Token JWT válido (auto-bootstrap vía scripts/dev/start-client-test-node.sh)
#
# Uso:
#   ./scripts/ops/load-test-smoke.sh                          # 4 endpoints default
#   ./scripts/ops/load-test-smoke.sh --bootstrap              # arranca nodo H2
#   ./scripts/ops/load-test-smoke.sh --requests 500 --concurrency 5
#   ./scripts/ops/load-test-smoke.sh --help
#
# Salidas:
#   exit 0       run completado (ver summary)
#   exit !=0     bootstrap o run de ab falló
#   artifacts    docs/operations/load-test-evidence/ab-<timestamp>-*.log + summary.txt

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

NODE_BASE_URL="${NODE_BASE_URL:-http://localhost:8081}"
TEST_USERNAME="${TEST_USERNAME:-tfg_client_test}"
TEST_PASSWORD="${TEST_PASSWORD:-NodeClient2026!}"
REQUESTS="1000"
CONCURRENCY="10"
TIMELIMIT="30"
BOOTSTRAP="false"

print_usage() {
  cat <<EOF
Usage: $0 [options]

HTTP load smoke con Apache Bench (4 endpoints clave). Captura RPS + p50/p95/p99
en docs/operations/load-test-evidence/ab-<timestamp>-*.log

Options:
  --bootstrap          Arranca nodo H2 antes de los tests (via start-client-test-node.sh).
  --requests N         Total requests por endpoint (default: 1000).
  --concurrency N      Concurrent requests (default: 10).
  --timelimit S        Max seconds por endpoint (default: 30).
  --help               Muestra esta ayuda.

Pre-requisitos verificados al arranque: ab, curl, python3.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bootstrap) BOOTSTRAP="true" ;;
    --requests) REQUESTS="$2"; shift ;;
    --concurrency) CONCURRENCY="$2"; shift ;;
    --timelimit) TIMELIMIT="$2"; shift ;;
    --help|-h) print_usage; exit 0 ;;
    *) echo "ERROR: unknown option '$1'"; print_usage; exit 1 ;;
  esac
  shift
done

for cmd in ab curl python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $cmd" >&2
    echo "  - macOS: ab y curl son default; python3 via brew o python.org" >&2
    echo "  - Linux: apt install apache2-utils curl python3" >&2
    exit 1
  fi
done

TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
EVIDENCE_DIR="$ROOT_DIR/docs/operations/load-test-evidence"
mkdir -p "$EVIDENCE_DIR"
SUMMARY_FILE="$EVIDENCE_DIR/ab-${TIMESTAMP}-summary.txt"

if [[ "$BOOTSTRAP" == "true" ]]; then
  echo "==> Bootstrap nodo H2 (USE_H2=true)..."
  USE_H2=true bash "$ROOT_DIR/scripts/dev/start-client-test-node.sh" >/dev/null
fi

echo "==> Login + obtener JWT token..."
LOGIN_BODY="{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
TOKEN=$(curl -s -X POST "$NODE_BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$LOGIN_BODY" | python3 -c "import sys, json; print(json.load(sys.stdin)['token'])")

if [[ -z "$TOKEN" ]]; then
  echo "ERROR: login falló — verifica que el nodo está arrancado en $NODE_BASE_URL" >&2
  exit 1
fi

run_endpoint() {
  local label="$1"
  local method="$2"
  local path="$3"
  local body_file="${4:-}"
  local headers_extra="${5:-}"

  local out_log="$EVIDENCE_DIR/ab-${TIMESTAMP}-${label}.log"
  echo
  echo "=== ${label}: ${method} ${path} ==="

  local cmd=(ab -n "$REQUESTS" -c "$CONCURRENCY" -t "$TIMELIMIT" -k)
  cmd+=(-H "Authorization: Bearer $TOKEN")
  if [[ -n "$headers_extra" ]]; then
    cmd+=(-H "$headers_extra")
  fi
  if [[ "$method" == "POST" && -n "$body_file" ]]; then
    cmd+=(-T "application/json" -p "$body_file")
  fi
  cmd+=("${NODE_BASE_URL}${path}")

  if "${cmd[@]}" 2>&1 | tee "$out_log" | tail -25; then
    grep -E "Requests per second|Time per request|50%|95%|99%|Failed requests" "$out_log" \
      | sed "s|^|  [$label] |"
    {
      echo "[$label] ${method} ${path}"
      grep -E "Requests per second|Time per request|50%|95%|99%|Failed requests" "$out_log"
      echo
    } >> "$SUMMARY_FILE"
  else
    echo "  ERROR: ab falló para ${label}"
    echo "[$label] ${method} ${path} — FAILED" >> "$SUMMARY_FILE"
  fi
}

# Body files temporales
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/login.json" <<EOF
{"username":"$TEST_USERNAME","password":"$TEST_PASSWORD"}
EOF

cat > "$TMP_DIR/fs-entry.json" <<'EOF'
{"path":"/load-test/file.txt","sizeBytes":1024,"checksum":"a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"}
EOF

{
  echo "Load test smoke summary"
  echo "Timestamp UTC: $TIMESTAMP"
  echo "Node base URL: $NODE_BASE_URL"
  echo "Config: -n $REQUESTS -c $CONCURRENCY -t $TIMELIMIT -k"
  echo "Hardware: $(uname -a)"
  echo "Java: $(java -version 2>&1 | head -1)"
  echo "==================================================================="
  echo
} > "$SUMMARY_FILE"

run_endpoint "auth-login" "POST" "/auth/login" "$TMP_DIR/login.json" ""
run_endpoint "sync-changes" "GET" "/sync/changes?since=0" "" ""
run_endpoint "fs-entries-create" "POST" "/fs/entries" "$TMP_DIR/fs-entry.json" ""
run_endpoint "actuator-prometheus" "GET" "/actuator/prometheus" "" ""

echo
echo "==> Summary completo en: $SUMMARY_FILE"
cat "$SUMMARY_FILE"
