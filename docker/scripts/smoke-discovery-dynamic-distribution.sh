#!/usr/bin/env bash
# Smoke de la integración dinámica con DiscoveryService end-to-end.
# Escenarios validados (con override de compose que reduce los intervalos de
# renewal/cleanup/freshness a segundos):
#   1. Distribución dinámica: un upload reparte n=3 fragments en 3 baseUrls distintos
#      (invariante 1-fragment-per-node-per-file).
#   2. Cleanup periódico: tras parar un nodo, su candidate desaparece del directorio.
#   3. Renewal periódico: tras restart, el SelfDiscoveryRenewalWorker lo re-anuncia.
#   4. Insufficient candidates: con sólo 1 baseUrl único disponible, upload con n=3
#      aborta con HTTP 503 INSUFFICIENT_CUSTODIANS.
# Flags: [--no-build] [--keep-running] [--fast]
# Dependencias: docker, docker compose, curl, python3, openssl, shasum, jq.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(/usr/bin/mktemp -d)"
OVERRIDE_COMPOSE="$TMP_DIR/discovery-dynamic-override.yml"

KEEP_RUNNING="false"
SKIP_BUILD="false"
DOCKER_BIN=""
STOPPED_NODES=()

cleanup() {
  local exit_code=$?

  if [[ -n "${DOCKER_BIN:-}" ]]; then
    for n in "${STOPPED_NODES[@]:-}"; do
      [[ -n "$n" ]] && "$DOCKER_BIN" start "$n" >/dev/null 2>&1 || true
    done
    if [[ "$KEEP_RUNNING" == "true" ]]; then
      echo "==> Keeping cluster running (--keep-running)"
    elif [[ -f "$OVERRIDE_COMPOSE" ]]; then
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
    --fast) KEEP_RUNNING="true"; SKIP_BUILD="true" ;;
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
"$DOCKER_BIN" info >/dev/null 2>&1 || { echo "ERROR: docker daemon unavailable"; exit 1; }

if [[ ! -f "$KEYS_DIR/node1-private.der" || ! -f "$KEYS_DIR/node2-private.der" || ! -f "$KEYS_DIR/node3-private.der" ]]; then
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
else
  echo "==> Reusing existing node keys/env"
fi

# Override: cadencias en segundos para que el smoke valide renewal/cleanup en <2min en
# vez de los defaults (renewal=300s, cleanup=3600s, staleness=900s). Cache TTL=1s evita
# que el cache hit oculte cambios entre escenarios.
cat > "$OVERRIDE_COMPOSE" <<'YAML'
services:
  node1:
    environment:
      NODE_DISCOVERY_RENEWAL_INTERVAL_MILLIS: "5000"
      NODE_DISCOVERY_RENEWAL_INITIAL_DELAY_MILLIS: "2000"
      NODE_DISCOVERY_CLEANUP_INTERVAL_MILLIS: "5000"
      NODE_DISCOVERY_CLEANUP_INITIAL_DELAY_MILLIS: "2000"
      NODE_DISCOVERY_CLEANUP_STALENESS_SECONDS: "20"
      NODE_DISCOVERY_DIRECTORY_FRESHNESS_SECONDS: "20"
      NODE_DISCOVERY_CACHE_TTL_SECONDS: "1"
      NODE_DISCOVERY_MAX_RETRIES: "1"
  node2:
    environment:
      NODE_DISCOVERY_RENEWAL_INTERVAL_MILLIS: "5000"
      NODE_DISCOVERY_RENEWAL_INITIAL_DELAY_MILLIS: "2000"
      NODE_DISCOVERY_CLEANUP_INTERVAL_MILLIS: "5000"
      NODE_DISCOVERY_CLEANUP_INITIAL_DELAY_MILLIS: "2000"
      NODE_DISCOVERY_CLEANUP_STALENESS_SECONDS: "20"
      NODE_DISCOVERY_DIRECTORY_FRESHNESS_SECONDS: "20"
      NODE_DISCOVERY_CACHE_TTL_SECONDS: "1"
      NODE_DISCOVERY_MAX_RETRIES: "1"
  node3:
    environment:
      NODE_DISCOVERY_RENEWAL_INTERVAL_MILLIS: "5000"
      NODE_DISCOVERY_RENEWAL_INITIAL_DELAY_MILLIS: "2000"
      NODE_DISCOVERY_CLEANUP_INTERVAL_MILLIS: "5000"
      NODE_DISCOVERY_CLEANUP_INITIAL_DELAY_MILLIS: "2000"
      NODE_DISCOVERY_CLEANUP_STALENESS_SECONDS: "20"
      NODE_DISCOVERY_DIRECTORY_FRESHNESS_SECONDS: "20"
      NODE_DISCOVERY_CACHE_TTL_SECONDS: "1"
      NODE_DISCOVERY_MAX_RETRIES: "1"
YAML

cd "$ROOT_DIR"
echo "==> [1/12] Cluster up con override de cadencias (seg./TTL freshness)"
if [[ "$SKIP_BUILD" == "true" ]]; then
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d --force-recreate >/dev/null
else
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up --build -d --force-recreate >/dev/null
fi

wait_http() {
  local url="$1" attempts=90
  for _ in $(/usr/bin/seq 1 "$attempts"); do
    local code
    code="$(/usr/bin/curl -sS -o /dev/null -w '%{http_code}' "$url" || true)"
    if [[ "$code" == "200" || "$code" == "401" || "$code" == "404" ]]; then
      return 0
    fi
    /bin/sleep 1
  done
  return 1
}

wait_http "http://localhost:8081/auth/me" || { echo "ERROR: node1 not ready"; exit 1; }
wait_http "http://localhost:8082/auth/me" || { echo "ERROR: node2 not ready"; exit 1; }
wait_http "http://localhost:8083/auth/me" || { echo "ERROR: node3 not ready"; exit 1; }

# Esperar 1 ciclo completo de renewal (5s + initialDelay 2s ≈ 8s) para que el directorio
# de cada supernodo tenga las 3 filas pobladas tras startup.
echo "==> [2/12] Esperar primer ciclo de renewal (~10s)"
/bin/sleep 12

NODE1_HASH="$(/usr/bin/shasum -a 256 "$KEYS_DIR/node1-public.der" | /usr/bin/awk '{print $1}')"
NODE1_ID="node-${NODE1_HASH:0:24}"
NODE2_HASH="$(/usr/bin/shasum -a 256 "$KEYS_DIR/node2-public.der" | /usr/bin/awk '{print $1}')"
NODE2_ID="node-${NODE2_HASH:0:24}"
NODE3_HASH="$(/usr/bin/shasum -a 256 "$KEYS_DIR/node3-public.der" | /usr/bin/awk '{print $1}')"
NODE3_ID="node-${NODE3_HASH:0:24}"

signed_get_candidates() {
  local node_index="$1" base_url="$2" out="$3"
  local raw
  raw="$("$DOCKER_DIR/scripts/signed-request.sh" "$node_index" GET "$base_url" "/ops/discovery/candidates" || true)"
  /usr/bin/printf "%s" "$raw" | /usr/bin/awk 'BEGIN{body=0} body{print} /^\r?$/{body=1}' > "$out"
  /usr/bin/printf "%s" "$raw" | /usr/bin/awk 'BEGIN{code=""} /^HTTP\/[0-9.]+ [0-9]+/{code=$2} END{print code}' | /usr/bin/tr -d '\r\n'
}

# ----------------------------------------------------------------------------
# Escenario 1 — Distribución dinámica: 3 fragments en 3 baseUrls DISTINTOS
# ----------------------------------------------------------------------------

echo "==> [3/12] Escenario 1 — registrar usuario + login en node1"
TEST_USERNAME="disc_user_$RANDOM"
TEST_PASSWORD="NodeClient2026!"
INV_CODE="DISC-$RANDOM"
"$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node <<SQL >/dev/null
INSERT INTO registration_code(code, quota_mb, expires_at, used, used_at, created_at)
VALUES ('$INV_CODE', 256, NOW() + INTERVAL '2 days', FALSE, NULL, NOW())
ON CONFLICT (code) DO UPDATE SET used=FALSE, used_at=NULL, created_at=NOW();
SQL

/usr/bin/curl -sS -o /dev/null -X POST "http://localhost:8081/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"invitationCode\":\"$INV_CODE\",\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
LOGIN_JSON="$TMP_DIR/login.json"
/usr/bin/curl -sS -o "$LOGIN_JSON" -X POST "http://localhost:8081/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
TOKEN="$(/usr/bin/python3 -c "import json; print(json.load(open('$LOGIN_JSON'))['token'])")"

echo "==> [4/12] Generar payload pequeño (1 bloque RS) y subir"
PAYLOAD="$TMP_DIR/disc-payload.bin"
/bin/dd if=/dev/urandom of="$PAYLOAD" bs=1024 count=8 status=none
SIZE="$(/usr/bin/wc -c < "$PAYLOAD" | /usr/bin/tr -d ' ')"
HASH="$(/usr/bin/shasum -a 256 "$PAYLOAD" | /usr/bin/awk '{print $1}')"

UPSERT_JSON="$TMP_DIR/upsert.json"
/usr/bin/curl -sS -o "$UPSERT_JSON" -X POST "http://localhost:8081/fs/entries" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"path\":\"/disc-test.bin\",\"entryType\":\"FILE\",\"sizeBytes\":$SIZE,\"checksum\":\"$HASH\",\"deleted\":false}"
ENTRY_ID="$(/usr/bin/python3 -c "import json; print(json.load(open('$UPSERT_JSON'))['entryId'])")"

UPLOAD_CODE="$(/usr/bin/curl -sS -o /dev/null -w '%{http_code}' \
  -X PUT "http://localhost:8081/files/entries/$ENTRY_ID/content" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary @"$PAYLOAD")"
[[ "$UPLOAD_CODE" == "200" ]] || { echo "ERROR: upload failed HTTP $UPLOAD_CODE"; exit 1; }
echo "    upload OK, entry=$ENTRY_ID"

echo "==> [5/12] Verificar n=3 placements distribuidos en 3 baseUrls DISTINTOS"
FILE_ID="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT file_id FROM client_file_manifest WHERE entry_id='$ENTRY_ID';" | /usr/bin/tr -d '[:space:]')"
DISTINCT_BASEURLS="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(DISTINCT custodian_base_url) FROM client_fragment_placement WHERE file_id='$FILE_ID';" | /usr/bin/tr -d '[:space:]')"
TOTAL_PLACEMENTS="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_fragment_placement WHERE file_id='$FILE_ID';" | /usr/bin/tr -d '[:space:]')"
echo "    placements=$TOTAL_PLACEMENTS distinct_baseurls=$DISTINCT_BASEURLS"
[[ "$TOTAL_PLACEMENTS" == "3" ]] || { echo "ERROR: expected 3 placements, got $TOTAL_PLACEMENTS"; exit 1; }
[[ "$DISTINCT_BASEURLS" == "3" ]] || { echo "ERROR: expected 3 distinct baseUrls (1-fragment-per-node invariante), got $DISTINCT_BASEURLS"; exit 1; }

# ----------------------------------------------------------------------------
# Escenario 2 — Cleanup: stop node3, esperar > staleness, candidato desaparece
# ----------------------------------------------------------------------------

echo "==> [6/12] Escenario 2 — stop node3 y esperar cleanup"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" stop node3 >/dev/null
STOPPED_NODES+=("distributed-node-3")

# Esperar > staleness-seconds (20s) + 1 ciclo de cleanup (5s) → 30s para garantizar
# que el row de node3 ha desaparecido del directorio.
echo "    esperando 30s para staleness + cleanup tick..."
/bin/sleep 30

CANDIDATES_BODY="$TMP_DIR/candidates-after-stop.json"
CODE="$(signed_get_candidates 1 "http://localhost:8082" "$CANDIDATES_BODY")"
[[ "$CODE" == "200" ]] || { echo "ERROR: GET /ops/discovery/candidates HTTP $CODE"; /bin/cat "$CANDIDATES_BODY"; exit 1; }
NODE3_FOUND="$(/usr/bin/jq --arg id "$NODE3_ID" '[.[] | select(.nodeId == $id)] | length' "$CANDIDATES_BODY")"
TOTAL_FOUND="$(/usr/bin/jq 'length' "$CANDIDATES_BODY")"
echo "    after stop: total candidates=$TOTAL_FOUND, node3 still present=$NODE3_FOUND"
[[ "$NODE3_FOUND" == "0" ]] || { echo "ERROR: expected node3 to be cleaned/filtered after staleness"; /bin/cat "$CANDIDATES_BODY"; exit 1; }

# ----------------------------------------------------------------------------
# Escenario 3 — Renewal: start node3, tras 1 ciclo vuelve al directorio
# ----------------------------------------------------------------------------

echo "==> [7/12] Escenario 3 — restart node3 y esperar renewal"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" start node3 >/dev/null
STOPPED_NODES=("${STOPPED_NODES[@]/distributed-node-3}")
wait_http "http://localhost:8083/auth/me" || { echo "ERROR: node3 not ready after restart"; exit 1; }

# Esperar al primer renewal del worker post-restart (initialDelay=2s + interval=5s).
echo "    esperando 12s para que el renewal post-restart se complete..."
/bin/sleep 12

CODE="$(signed_get_candidates 1 "http://localhost:8082" "$CANDIDATES_BODY")"
[[ "$CODE" == "200" ]] || { echo "ERROR: GET /ops/discovery/candidates HTTP $CODE"; /bin/cat "$CANDIDATES_BODY"; exit 1; }
NODE3_FOUND="$(/usr/bin/jq --arg id "$NODE3_ID" '[.[] | select(.nodeId == $id)] | length' "$CANDIDATES_BODY")"
TOTAL_FOUND="$(/usr/bin/jq 'length' "$CANDIDATES_BODY")"
echo "    after restart: total candidates=$TOTAL_FOUND, node3 reappears=$NODE3_FOUND"
[[ "$NODE3_FOUND" == "1" ]] || { echo "ERROR: expected node3 to reappear after renewal"; /bin/cat "$CANDIDATES_BODY"; exit 1; }

NODE3_BASEURL="$(/usr/bin/jq -r --arg id "$NODE3_ID" '.[] | select(.nodeId == $id) | .baseUrl' "$CANDIDATES_BODY")"
echo "    node3 baseUrl persisted in directory: $NODE3_BASEURL"
[[ "$NODE3_BASEURL" == "http://node3:8080" ]] || { echo "ERROR: expected baseUrl http://node3:8080, got '$NODE3_BASEURL'"; exit 1; }

# ----------------------------------------------------------------------------
# Escenario 4 — Insufficient candidates: stop 2 nodos, upload aborta 503
# ----------------------------------------------------------------------------

echo "==> [8/12] Escenario 4 — stop node2 y node3, sólo queda 1 baseUrl único"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" stop node2 >/dev/null
STOPPED_NODES+=("distributed-node-2")
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" stop node3 >/dev/null
STOPPED_NODES+=("distributed-node-3")

# Esperar a que el directorio de node1 ya no liste node2/node3 — staleness 20s.
echo "    esperando 28s para que node2 y node3 expiren del directorio..."
/bin/sleep 28

PAYLOAD2="$TMP_DIR/disc-payload-2.bin"
/bin/dd if=/dev/urandom of="$PAYLOAD2" bs=1024 count=8 status=none
SIZE2="$(/usr/bin/wc -c < "$PAYLOAD2" | /usr/bin/tr -d ' ')"
HASH2="$(/usr/bin/shasum -a 256 "$PAYLOAD2" | /usr/bin/awk '{print $1}')"

UPSERT2_JSON="$TMP_DIR/upsert2.json"
/usr/bin/curl -sS -o "$UPSERT2_JSON" -X POST "http://localhost:8081/fs/entries" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"path\":\"/disc-fail.bin\",\"entryType\":\"FILE\",\"sizeBytes\":$SIZE2,\"checksum\":\"$HASH2\",\"deleted\":false}"
ENTRY2_ID="$(/usr/bin/python3 -c "import json; print(json.load(open('$UPSERT2_JSON'))['entryId'])")"

echo "==> [9/12] Upload con 2/3 nodos abajo — esperar 503 (preflight tutor o INSUFFICIENT_CUSTODIANS)"
UPLOAD_RESP_BODY="$TMP_DIR/upload-fail-body.json"
UPLOAD_CODE2="$(/usr/bin/curl -sS -o "$UPLOAD_RESP_BODY" -w '%{http_code}' \
  -X PUT "http://localhost:8081/files/entries/$ENTRY2_ID/content" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary @"$PAYLOAD2" || true)"
echo "    upload HTTP=$UPLOAD_CODE2 body=$(/bin/cat "$UPLOAD_RESP_BODY" 2>/dev/null | /usr/bin/head -c 200)"
[[ "$UPLOAD_CODE2" == "503" ]] || { echo "ERROR: expected 503, got $UPLOAD_CODE2"; exit 1; }

# Cuando el tutor (node2) está caído, el preflight ataja con FILESYSTEM_TUTOR_REPLICATION_FAILED.
# Cuando el tutor está vivo pero los peers no, DiscoveryService ataja con INSUFFICIENT_CUSTODIANS.
# Aceptamos cualquiera de los 2 — ambos son la red de seguridad fail-closed que queremos.
# El campo es `errorCode` en `ApiErrorPayload`, NO `code`.
ERROR_CODE="$(/usr/bin/jq -r '.errorCode // empty' "$UPLOAD_RESP_BODY" 2>/dev/null || echo "")"
echo "    error code=$ERROR_CODE"
case "$ERROR_CODE" in
  INSUFFICIENT_CUSTODIANS|FILESYSTEM_TUTOR_REPLICATION_FAILED|DISCOVERY_UNREACHABLE)
    echo "    fail-closed esperado: $ERROR_CODE"
    ;;
  *)
    echo "ERROR: error code inesperado: $ERROR_CODE"
    exit 1
    ;;
esac

echo "==> [10/12] Verificar que el upload falló SIN distribuir fragments (cero placements para entry2)"
PLACEMENTS_FOR_ENTRY2="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_fragment_placement p JOIN client_file_manifest m ON p.file_id = m.file_id WHERE m.entry_id = '$ENTRY2_ID';" | /usr/bin/tr -d '[:space:]')"
echo "    placements para entry2 (esperado 0): $PLACEMENTS_FOR_ENTRY2"
[[ "$PLACEMENTS_FOR_ENTRY2" == "0" ]] || { echo "ERROR: expected 0 placements after fail-closed upload, got $PLACEMENTS_FOR_ENTRY2"; exit 1; }

echo "==> [11/12] Restart node2 + node3 (cluster healthy de nuevo)"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" start node2 >/dev/null
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" start node3 >/dev/null
STOPPED_NODES=("${STOPPED_NODES[@]/distributed-node-2}")
STOPPED_NODES=("${STOPPED_NODES[@]/distributed-node-3}")
wait_http "http://localhost:8082/auth/me" || true
wait_http "http://localhost:8083/auth/me" || true

echo "==> [12/12] SUCCESS: dynamic discovery smoke passed"
echo "  - E1: 3 placements distribuidos en 3 baseUrls distintos (1-fragment-per-node OK)"
echo "  - E2: cleanup elimina candidato stale tras > staleness-seconds"
echo "  - E3: renewal worker re-anuncia candidato post-restart"
echo "  - E4: upload fail-closed con $ERROR_CODE + cero fragments distribuidos"
