#!/usr/bin/env bash
# Smoke regression del flujo recovery con node.recovery.mode=RESTORE.
# Escenario: upload + replica manifest al tutor → fatal failure de node1 (drop volume) →
# restore users + arranque con NODE_RECOVERY_MODE=RESTORE → verifica reconstrucción del
# catalog (fs_entry + client_file_manifest + client_fragment_placement) y download.
# Flags: [--no-build] [--keep-running] [--fast]
# Dependencias: docker, docker compose, curl, python3, openssl, shasum, pg_dump (in-container).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

KEEP_RUNNING="false"
SKIP_BUILD="false"
TEST_USERNAME="recovery_smoke_$RANDOM"
TEST_PASSWORD="NodeClient2026!"
TEST_QUOTA_MB=2048

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

if has_key_material() {
  [[ -f "$KEYS_DIR/node1-private.der" && -f "$DOCKER_DIR/env/node1.env" ]]
}; then :; fi

if [[ ! -f "$KEYS_DIR/node1-private.der" ]]; then
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
fi

cd "$ROOT_DIR"
# Override compose: deshabilita custody-liveness durante el smoke. El test valida el flow
# RESTORE end-to-end (manifest replication + bootstrap runner + reconstruct). En CI Linux
# el cluster tarda >60s entre el stop+wipe (step 6) y el restore (step 8), lo que da
# tiempo al custody-liveness de los peers (BASE_INTERVAL=30s + FAST_RETRY×3=30s) a probear
# node1 caído, marcarlo UNRESPONSIVE y escalar los fragments via RETURN_TO_TUTOR. Cuando
# node1 vuelve, los fragments ya no están en custody y el reconstruct falla con
# FILE_IRRECOVERABLE. Deshabilitar el módulo aísla el smoke del custody-liveness sin
# afectar lo que valida.
OVERRIDE_COMPOSE="$TMP_DIR/restore-override.yml"
cat > "$OVERRIDE_COMPOSE" <<'YAML'
services:
  node1:
    environment:
      NODE_CUSTODY_LIVENESS_ENABLED: "false"
  node2:
    environment:
      NODE_CUSTODY_LIVENESS_ENABLED: "false"
  node3:
    environment:
      NODE_CUSTODY_LIVENESS_ENABLED: "false"
YAML

echo "==> [1/10] Cluster up"
if [[ "$SKIP_BUILD" == "true" ]]; then
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d --force-recreate >/dev/null
else
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up --build -d --force-recreate >/dev/null
fi

wait_http() {
  local url="$1" attempts=90
  for _ in $(seq 1 $attempts); do
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' "$url" || true)"
    [[ "$code" == "200" || "$code" == "401" || "$code" == "404" ]] && return 0
    sleep 1
  done
  return 1
}
wait_http "http://localhost:8081/auth/me" || { echo "node1 not ready"; exit 1; }
wait_http "http://localhost:8082/auth/me" || { echo "node2 not ready"; exit 1; }
wait_http "http://localhost:8083/auth/me" || { echo "node3 not ready"; exit 1; }

# CI Linux runner: discovery propagation puede tardar significativamente más que macOS
# local (Spring Boot startup +SelfDiscoveryRegistrar HTTP roundtrips). El upload del
# step 3 requiere n=3 custodians; arrancar pre-convergencia produce 503
# INSUFFICIENT_CUSTODIANS.
wait_discovery_convergence() {
  local last_count="?"
  for i in $(seq 1 240); do
    local count
    count="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A -c \
      "SELECT COUNT(DISTINCT node_id) FROM discovery_candidate WHERE healthy=TRUE;" 2>/dev/null | tr -d '[:space:]')"
    if [[ -n "$count" && "$count" -ge 3 ]]; then
      echo "  discovery converged: $count healthy candidates after ${i}s"
      return 0
    fi
    last_count="$count"
    sleep 1
  done
  echo "ERROR: discovery directory did not converge to ≥3 healthy candidates after 240s (last count: $last_count)"
  echo "  -- discovery_candidate snapshot --"
  "$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -c \
    "SELECT node_id, base_url, healthy, last_seen_at FROM discovery_candidate ORDER BY node_id;" 2>/dev/null || true
  echo "  -- node1 log tail --"
  "$DOCKER_BIN" logs --tail 50 distributed-node-1 2>&1 | grep -iE "discovery|self-disc|register|supern|warn|error" | tail -30 || true
  return 1
}
wait_discovery_convergence || exit 1

echo "==> [2/10] Register user + login"
INV_CODE="RECOV-$RANDOM"
"$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node <<SQL >/dev/null
INSERT INTO registration_code(code, quota_mb, expires_at, used, used_at, created_at)
VALUES ('$INV_CODE', $TEST_QUOTA_MB, NOW() + INTERVAL '2 days', FALSE, NULL, NOW())
ON CONFLICT (code) DO UPDATE SET used=FALSE, used_at=NULL, created_at=NOW();
SQL

curl -sS -o /dev/null -X POST "http://localhost:8081/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"invitationCode\":\"$INV_CODE\",\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"

LOGIN_JSON="$TMP_DIR/login.json"
curl -sS -o "$LOGIN_JSON" -X POST "http://localhost:8081/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
TOKEN="$(python3 -c "import json; print(json.load(open('$LOGIN_JSON'))['token'])")"

echo "==> [3/10] Upload 1 file"
PAYLOAD="$TMP_DIR/payload.txt"
echo "Recovery restore-mode smoke at $(date -u +%FT%TZ)" > "$PAYLOAD"
SIZE="$(wc -c < "$PAYLOAD" | tr -d ' ')"
HASH="$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')"

UPSERT_JSON="$TMP_DIR/upsert.json"
curl -sS -o "$UPSERT_JSON" -X POST "http://localhost:8081/fs/entries" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"path\":\"/recovery-test.txt\",\"entryType\":\"FILE\",\"sizeBytes\":$SIZE,\"checksum\":\"$HASH\",\"deleted\":false}"
ENTRY_ID="$(python3 -c "import json; print(json.load(open('$UPSERT_JSON'))['entryId'])")"

curl -sS -X PUT "http://localhost:8081/files/entries/$ENTRY_ID/content" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary @"$PAYLOAD" >/dev/null

echo "==> [4/10] Verify replication state"
NODE1_MANIFEST_COUNT="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_file_manifest WHERE username='$TEST_USERNAME';" | tr -d '[:space:]')"
NODE1_PLACEMENT_COUNT="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_fragment_placement;" | tr -d '[:space:]')"
NODE1_ID="node-$(shasum -a 256 "$KEYS_DIR/node1-public.der" | awk '{print substr($1,1,24)}')"
TUTOR_MANIFEST_COUNT="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM recovery_file_manifest WHERE requester_node_id='$NODE1_ID';" 2>/dev/null | tr -d '[:space:]' || echo 0)"

echo "  node1 client_file_manifest: $NODE1_MANIFEST_COUNT"
echo "  node1 client_fragment_placement: $NODE1_PLACEMENT_COUNT"
echo "  tutor recovery_file_manifest: $TUTOR_MANIFEST_COUNT"
[[ "$NODE1_MANIFEST_COUNT" == "1" ]] || { echo "ERROR: node1 manifest missing"; exit 1; }
[[ "$NODE1_PLACEMENT_COUNT" -ge "1" ]] || { echo "ERROR: node1 placements missing"; exit 1; }

echo "==> [5/10] Backup users from node1"
BACKUP_FILE="$TMP_DIR/users-backup.dump"
"$DOCKER_BIN" exec node-postgres-1 pg_dump -U node -d node \
  --format=custom --table=user_account --table=user_session --table=fs_entry \
  --table=registration_code > "$BACKUP_FILE"
echo "  backup size: $(wc -c < "$BACKUP_FILE" | tr -d ' ') bytes"

echo "==> [6/10] Fatal failure: stop + wipe Postgres-node1"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" stop node1 postgres-node1 >/dev/null
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" rm -fv postgres-node1 >/dev/null

echo "==> [7/10] Bring Postgres-node1 back fresh + boot node1 normalmente para que Flyway cree schema"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d postgres-node1 >/dev/null
sleep 5
"$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node -c "SELECT 1;" >/dev/null
# Boot node1 en modo NORMAL primero para que Flyway aplique migrations sobre BD vacía.
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d node1 >/dev/null
wait_http "http://localhost:8081/auth/me" || { echo "node1 didn't come back up after wipe"; exit 1; }
echo "  Flyway aplicó schema; ahora restauro users encima"
# Restore users on top of the empty-but-schema'd DB. pg_restore --clean drops existing rows.
"$DOCKER_BIN" exec -i node-postgres-1 pg_restore -U node -d node --data-only --no-owner < "$BACKUP_FILE" 2>&1 | tail -5 || true

echo "==> [8/10] Restart node1 in RESTORE mode"
# Append recovery env vars al env_file (forma fiable de propagar a container via docker compose).
# El bootstrap runner sólo reconstruye el catalog post-restore; el FileIntegrityRiskOrchestrator
# toma el relevo en operación normal.
ENV_FILE="$ROOT_DIR/docker/env/node1.env"
ENV_BACKUP="$TMP_DIR/node1.env.original"
cp "$ENV_FILE" "$ENV_BACKUP"
trap 'cp "$ENV_BACKUP" "$ENV_FILE" 2>/dev/null || true; rm -rf "$TMP_DIR"' EXIT
{
  echo "NODE_RECOVERY_MODE=RESTORE"
} >> "$ENV_FILE"
"$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" up -d --force-recreate node1 >/dev/null
wait_http "http://localhost:8081/auth/me" || { echo "node1 didn't come back up in RESTORE mode"; exit 1; }
# El RecoveryBootstrapRunner es ApplicationRunner: corre tras bind del web server,
# pero /auth/me responde antes de que termine. runLazyPass(placements) hace HTTP
# fetches sincrónicos a peers (inventory) — lento en CI ubuntu (vs macOS local).
# Esperamos hasta 30s a que el log "Bootstrap recovery completed" aparezca, con
# fallback a sleep 10 si no lo vemos (defensa en profundidad).
echo "  waiting for bootstrap runner to complete..."
for _ in $(seq 1 30); do
  if "$DOCKER_BIN" logs distributed-node-1 2>&1 | grep -q "Bootstrap recovery completed"; then
    echo "  bootstrap runner completed"
    break
  fi
  sleep 1
done
sleep 2 # cushion: lazy pass puede aún estar finalizando placements post-log

echo "==> [9/10] Verify catalog reconstruction"
RESTORED_MANIFESTS="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_file_manifest WHERE username='$TEST_USERNAME';" | tr -d '[:space:]')"
RESTORED_PLACEMENTS="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_fragment_placement;" | tr -d '[:space:]')"
RESTORED_FS_ENTRIES="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM fs_entry WHERE username='$TEST_USERNAME';" | tr -d '[:space:]')"

echo "  restored client_file_manifest: $RESTORED_MANIFESTS (expected $NODE1_MANIFEST_COUNT)"
echo "  restored client_fragment_placement: $RESTORED_PLACEMENTS (expected $NODE1_PLACEMENT_COUNT)"
echo "  restored fs_entry: $RESTORED_FS_ENTRIES (expected ≥1)"

[[ "$RESTORED_MANIFESTS" == "$NODE1_MANIFEST_COUNT" ]] || { echo "ERROR: manifests not restored"; exit 1; }
[[ "$RESTORED_PLACEMENTS" == "$NODE1_PLACEMENT_COUNT" ]] || { echo "ERROR: placements not restored"; exit 1; }
[[ "$RESTORED_FS_ENTRIES" -ge "1" ]] || { echo "ERROR: fs_entry not restored"; exit 1; }

echo "==> [10/10] Login post-restore + verify download via reconstruct"
LOGIN2_JSON="$TMP_DIR/login2.json"
curl -sS -o "$LOGIN2_JSON" -X POST "http://localhost:8081/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
TOKEN2="$(python3 -c "import json; print(json.load(open('$LOGIN2_JSON'))['token'])")"

curl -sS -o "$TMP_DIR/tree.json" "http://localhost:8081/fs/tree" -H "Authorization: Bearer $TOKEN2"
RESTORED_PATH="$(python3 -c "
import json
data = json.load(open('$TMP_DIR/tree.json'))
for e in data.get('entries', []):
    if 'recovery-test' in e.get('path',''):
        print(e['path']); break
")"
[[ -n "$RESTORED_PATH" ]] || { echo "ERROR: restored file not visible in /fs/tree"; cat "$TMP_DIR/tree.json"; exit 1; }
echo "  restored path visible: $RESTORED_PATH"

DOWNLOADED="$TMP_DIR/downloaded.txt"
ENTRY_ID2="$(python3 -c "
import json
data = json.load(open('$TMP_DIR/tree.json'))
for e in data.get('entries', []):
    if 'recovery-test' in e.get('path',''):
        print(e['entryId']); break
")"
# Retry: el RecoveryBootstrapRunner LAZY pass puede aún estar redistribuyendo
# placements (peers más lentos en CI). El download falla con 503 transitorio
# mientras el catalog se estabiliza. 5 intentos × 4s = ~20s ventana.
DOWNLOAD_CODE=""
for attempt in 1 2 3 4 5; do
  DOWNLOAD_CODE="$(curl -sS -o "$DOWNLOADED" -w '%{http_code}' \
    "http://localhost:8081/files/entries/$ENTRY_ID2/content" \
    -H "Authorization: Bearer $TOKEN2")"
  if [[ "$DOWNLOAD_CODE" == "200" ]]; then
    break
  fi
  echo "  download attempt $attempt: HTTP $DOWNLOAD_CODE (retrying)..."
  sleep 4
done

if [[ "$DOWNLOAD_CODE" != "200" ]]; then
  echo "ERROR: download failed HTTP $DOWNLOAD_CODE after 5 attempts"
  echo "  -- Response body --"
  /bin/cat "$DOWNLOADED" 2>/dev/null | head -5
  echo "  -- node1 placements (custodians) --"
  "$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
    -c "SELECT fragment_id, custodian_node_id, custodian_base_url FROM client_fragment_placement;" 2>/dev/null
  echo "  -- node1 manifest --"
  "$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
    -c "SELECT file_id, redundancy_n, redundancy_k FROM client_file_manifest;" 2>/dev/null
  echo "  -- node1 self custody_fragment (post-redistribute) --"
  "$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
    -c "SELECT fragment_id, requester_node_id, expires_at FROM custody_fragment;" 2>/dev/null
  echo "  -- node2 custody_fragment (original peer) --"
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
    -c "SELECT fragment_id, requester_node_id FROM custody_fragment;" 2>/dev/null
  echo "  -- node3 custody_fragment (original peer) --"
  "$DOCKER_BIN" exec node-postgres-3 psql -U node -d node -t -A \
    -c "SELECT fragment_id, requester_node_id FROM custody_fragment;" 2>/dev/null
  echo "  -- node1 log tail (search bootstrap/restore/lazy/redistribute/503/IRRECOVER) --"
  "$DOCKER_BIN" logs --tail 300 distributed-node-1 2>&1 \
    | grep -E "Bootstrap recovery|FsRestore|Lazy pass|Redistribute|REDISTRIBUTE|RECOVER|IRRECOVER|InsufficientCustodian|Custodian fragment fetch failed" \
    | tail -80
  exit 1
fi
cmp -s "$PAYLOAD" "$DOWNLOADED" || { echo "ERROR: downloaded content mismatch"; exit 1; }

echo
echo "SUCCESS: recovery restore-mode smoke passed"
echo "  - User: $TEST_USERNAME"
echo "  - Manifests restored: $RESTORED_MANIFESTS"
echo "  - Placements restored: $RESTORED_PLACEMENTS"
echo "  - Download via reconstruct from surviving peers: OK"

if [[ "$KEEP_RUNNING" == "false" ]]; then
  "$DOCKER_BIN" compose -f docker-compose.yml -f "$OVERRIDE_COMPOSE" down -v >/dev/null
fi
