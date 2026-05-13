#!/usr/bin/env bash
# Smoke regression del flujo de recovery BYTES_FROM_TUTOR.
# Escenario: upload + migración custody → recovery_orphan vía SQL + restart de node1
# con NODE_RECOVERY_MODE=RESTORE → download con fallback a blob local desde el tutor.
# Flags: [--no-build] [--keep-running] [--fast]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

KEEP_RUNNING="false"
SKIP_BUILD="false"
TEST_USERNAME="bft_user_$RANDOM"
TEST_PASSWORD="NodeClient2026!"
TEST_QUOTA_MB=2048

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-running) KEEP_RUNNING="true" ;;
    --no-build) SKIP_BUILD="true" ;;
    --fast) KEEP_RUNNING="true"; SKIP_BUILD="true" ;;
    --help) grep '^#' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) echo "ERROR: unknown option '$1'"; exit 1 ;;
  esac
  shift
done

DOCKER_BIN="$(command -v docker || true)"
[[ -n "$DOCKER_BIN" ]] || { echo "ERROR: docker is required"; exit 1; }

if [[ ! -f "$KEYS_DIR/node1-private.der" ]]; then
  echo "==> Generating node keys/env"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
fi

cd "$ROOT_DIR"
echo "==> [1/9] Cluster up"
if [[ "$SKIP_BUILD" == "true" ]]; then
  "$DOCKER_BIN" compose up -d --force-recreate >/dev/null
else
  "$DOCKER_BIN" compose up --build -d --force-recreate >/dev/null
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
# local. El upload del step 2 requiere n=3 custodians; arrancar pre-convergencia produce
# 503 INSUFFICIENT_CUSTODIANS.
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
  return 1
}
wait_discovery_convergence || exit 1

echo "==> [2/9] Register user + upload 1 archivo"
INV_CODE="BFT-$RANDOM"
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

PAYLOAD="$TMP_DIR/payload.bin"
echo "BYTES_FROM_TUTOR smoke at $(date -u +%FT%TZ)" > "$PAYLOAD"
SIZE="$(wc -c < "$PAYLOAD" | tr -d ' ')"
HASH="$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')"

UPSERT_JSON="$TMP_DIR/upsert.json"
curl -sS -o "$UPSERT_JSON" -X POST "http://localhost:8081/fs/entries" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"path\":\"/bft-test.txt\",\"entryType\":\"FILE\",\"sizeBytes\":$SIZE,\"checksum\":\"$HASH\",\"deleted\":false}"
ENTRY_ID="$(python3 -c "import json; print(json.load(open('$UPSERT_JSON'))['entryId'])")"

curl -sS -X PUT "http://localhost:8081/files/entries/$ENTRY_ID/content" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary @"$PAYLOAD" >/dev/null

echo "==> [3/9] Verify upload OK (custody fragments + recovery_file_manifest@tutor)"
FRAG_COUNT_BEFORE="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM custody_fragment;" | tr -d '[:space:]')"
TUTOR_MANIFEST_COUNT="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM recovery_file_manifest;" | tr -d '[:space:]')"
echo "  node2 custody_fragment: $FRAG_COUNT_BEFORE"
echo "  node2 recovery_file_manifest: $TUTOR_MANIFEST_COUNT"
[[ "$TUTOR_MANIFEST_COUNT" -ge "1" ]] || { echo "ERROR: tutor manifest replication missing"; exit 1; }

echo "==> [4/9] Backup users from node1"
BACKUP_FILE="$TMP_DIR/users-backup.dump"
"$DOCKER_BIN" exec node-postgres-1 pg_dump -U node -d node \
  --format=custom --data-only \
  --table=user_account --table=user_session --table=registration_code \
  > "$BACKUP_FILE"

echo "==> [4b/9] Migrar custody@node1 → recovery_orphan@node2 ANTES del fatal failure"
# En el flow real, el peer node1 también escalaría sus propios fragments al tutor (node2)
# antes de caer. Aquí lo simulamos: node1 también custodia 1 de los 3 fragments RS(3,2) y se
# perdería con el fatal failure si no migramos primero.
NODE1_FRAGS_FILE="$TMP_DIR/n1-frags.csv"
"$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node -t -A -F'|' \
  -c "SELECT fragment_id, agreement_id, requester_node_id, checksum_algorithm, checksum, size_bytes, stored_at FROM custody_fragment;" \
  > "$NODE1_FRAGS_FILE"
while IFS='|' read -r fid aid req algo cks size sat; do
  [[ -z "$fid" ]] && continue
  # recovery_orphan_fragment no lleva expires_at (sin TTL).
  "$DOCKER_BIN" exec -i node-postgres-2 psql -U node -d node -c \
    "INSERT INTO recovery_orphan_fragment(fragment_id, agreement_id, requester_node_id, checksum_algorithm, checksum, size_bytes, stored_at) VALUES ('$fid', '$aid', '$req', '$algo', '$cks', $size, '$sat') ON CONFLICT (fragment_id) DO NOTHING;" \
    >/dev/null
  PAYLOAD_B64=$("$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node -t -A -c \
    "SELECT encode(payload, 'base64') FROM custody_fragment_payload WHERE fragment_id='$fid';" | tr -d '[:space:]')
  "$DOCKER_BIN" exec -i node-postgres-2 psql -U node -d node -c \
    "INSERT INTO recovery_orphan_fragment_payload(fragment_id, payload) VALUES ('$fid', decode('$PAYLOAD_B64', 'base64')) ON CONFLICT (fragment_id) DO NOTHING;" \
    >/dev/null
done < "$NODE1_FRAGS_FILE"

echo "==> [5/9] Simular RETURN_TO_TUTOR via SQL: copia custody_fragment→recovery_orphan_fragment en tutor (node2), borra custody en TODOS los peers"
# Migración SQL: en el flow real, peer X probea origen → unresponsive → escalation →
# fragments@X migran a tutor's recovery_orphan_fragment. Aquí lo simulamos con SQL para que
# el smoke valide el path BYTES_FROM_TUTOR sin depender de custody-liveness escalation timing.
#
# Migra TANTO desde node2 (tutor también es custodian) COMO desde node3 (peer puro). Los
# fragments de node1 (que era custodian de su propio file) ya se perdieron al wipear el volumen.

# 1. Migra in-place dentro de node2 (mismo Postgres)
# recovery_orphan_fragment no lleva expires_at (sin TTL).
"$DOCKER_BIN" exec -i node-postgres-2 psql -U node -d node <<'SQL'
INSERT INTO recovery_orphan_fragment(
  fragment_id, agreement_id, requester_node_id, checksum_algorithm, checksum, size_bytes,
  stored_at)
SELECT fragment_id, agreement_id, requester_node_id, checksum_algorithm, checksum, size_bytes,
  stored_at
FROM custody_fragment
ON CONFLICT (fragment_id) DO NOTHING;

INSERT INTO recovery_orphan_fragment_payload(fragment_id, payload)
SELECT fragment_id, payload FROM custody_fragment_payload
ON CONFLICT (fragment_id) DO NOTHING;

DELETE FROM custody_fragment_payload;
DELETE FROM custody_fragment;
SQL

# 2. Cross-node: copiar custody@node3 → recovery_orphan@node2 via stdout pipeline
NODE3_FRAGMENTS_SQL="$TMP_DIR/node3-fragments.sql"
"$DOCKER_BIN" exec -i node-postgres-3 psql -U node -d node -t -A -F'|' \
  -c "SELECT fragment_id, agreement_id, requester_node_id, checksum_algorithm, checksum, size_bytes, stored_at FROM custody_fragment;" \
  > "$TMP_DIR/n3-frags.csv"
NODE3_PAYLOADS_DIR="$TMP_DIR/n3-payloads"
mkdir -p "$NODE3_PAYLOADS_DIR"

while IFS='|' read -r fid aid req algo cks size sat; do
  [[ -z "$fid" ]] && continue
  # Insert metadata into node2.recovery_orphan_fragment (sin expires_at).
  "$DOCKER_BIN" exec -i node-postgres-2 psql -U node -d node -c \
    "INSERT INTO recovery_orphan_fragment(fragment_id, agreement_id, requester_node_id, checksum_algorithm, checksum, size_bytes, stored_at) VALUES ('$fid', '$aid', '$req', '$algo', '$cks', $size, '$sat') ON CONFLICT (fragment_id) DO NOTHING;" \
    >/dev/null
  # Copy payload bytes via base64 dance
  PAYLOAD_B64=$("$DOCKER_BIN" exec -i node-postgres-3 psql -U node -d node -t -A -c \
    "SELECT encode(payload, 'base64') FROM custody_fragment_payload WHERE fragment_id='$fid';" | tr -d '[:space:]')
  "$DOCKER_BIN" exec -i node-postgres-2 psql -U node -d node -c \
    "INSERT INTO recovery_orphan_fragment_payload(fragment_id, payload) VALUES ('$fid', decode('$PAYLOAD_B64', 'base64')) ON CONFLICT (fragment_id) DO NOTHING;" \
    >/dev/null
done < "$TMP_DIR/n3-frags.csv"

# 3. Borrar custody@node3 (post-migración)
"$DOCKER_BIN" exec -i node-postgres-3 psql -U node -d node <<'SQL' >/dev/null
DELETE FROM custody_fragment_payload;
DELETE FROM custody_fragment;
SQL

ORPHAN_COUNT="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM recovery_orphan_fragment;" | tr -d '[:space:]')"
CUSTODY_2_AFTER="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM custody_fragment;" | tr -d '[:space:]')"
CUSTODY_3_AFTER="$("$DOCKER_BIN" exec node-postgres-3 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM custody_fragment;" | tr -d '[:space:]')"
echo "  Post-migración: orphan@node2=$ORPHAN_COUNT, custody@node2=$CUSTODY_2_AFTER, custody@node3=$CUSTODY_3_AFTER"
# RS(3,2) → 3 fragments distribuidos. Tras migrar custody@{node1,node2,node3} → orphan@node2
# debemos tener los 3 fragments en orphan (node1 migrado en step 4b antes del fatal failure).
[[ "$ORPHAN_COUNT" -ge "3" ]] || { echo "ERROR: expected ≥3 orphan fragments (got $ORPHAN_COUNT)"; exit 1; }
[[ "$CUSTODY_2_AFTER" == "0" && "$CUSTODY_3_AFTER" == "0" ]] || { echo "ERROR: custody not fully drained"; exit 1; }

echo "==> [6/9] Fatal failure node1 + Postgres limpio + Flyway schema (sin levantar node1 todavía)"
"$DOCKER_BIN" compose stop node1 postgres-node1 >/dev/null
"$DOCKER_BIN" compose rm -fv postgres-node1 >/dev/null
"$DOCKER_BIN" compose up -d postgres-node1 >/dev/null
sleep 5
"$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node -c "SELECT 1;" >/dev/null
# pg_restore solo necesita postgres-1, no node1. Node1 NO se levanta aquí: el primer
# arranque de node1 post-wipe ocurre en el step 7 con NODE_RECOVERY_MODE=RESTORE +
# BYTES_FROM_TUTOR. Levantarlo aquí dispararía el bootstrap RESTORE prematuramente
# (con los env vars del compose), consumiría los orphans en un primer ciclo y dejaría
# al step 7 sin nada que reconstruir.
"$DOCKER_BIN" exec -i node-postgres-1 pg_restore -U node -d node --data-only --no-owner < "$BACKUP_FILE" 2>&1 | tail -3 || true

echo "==> [7/9] Arrancar node1 en RESTORE + BYTES_FROM_TUTOR (primer y único arranque post-wipe)"
ENV_FILE="$ROOT_DIR/docker/env/node1.env"
ENV_BACKUP="$TMP_DIR/node1.env.original"
cp "$ENV_FILE" "$ENV_BACKUP"
trap 'cp "$ENV_BACKUP" "$ENV_FILE" 2>/dev/null || true; rm -rf "$TMP_DIR"' EXIT
{
  echo "NODE_RECOVERY_MODE=RESTORE"
  # restore-strategy es el enum legacy (METADATA_ONLY|BYTES_FROM_TUTOR) que NodeFsRestoreService
  # consume via recoveryProperties.getRestoreStrategy(). El enum nuevo (METADATA|ACTIVE|LAZY) en
  # node.recovery.strategy es para el worker. BYTES_FROM_TUTOR vive en el legacy enum.
  echo "NODE_RECOVERY_RESTORE_STRATEGY=BYTES_FROM_TUTOR"
} >> "$ENV_FILE"
"$DOCKER_BIN" compose up -d node1 >/dev/null
wait_http "http://localhost:8081/auth/me" || { echo "node1 didn't come back in RESTORE mode"; exit 1; }
sleep 5  # bootstrap runner + reconstruct + reUpload + ACK orphans

echo "==> [8/9] Verify catalog + bytes"
RESTORED_MANIFESTS="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_file_manifest WHERE username='$TEST_USERNAME';" | tr -d '[:space:]')"
RESTORED_PLACEMENTS="$("$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM client_fragment_placement;" | tr -d '[:space:]')"
echo "  client_file_manifest: $RESTORED_MANIFESTS (expected ≥1)"
echo "  client_fragment_placement: $RESTORED_PLACEMENTS (expected ≥3)"
[[ "$RESTORED_MANIFESTS" -ge "1" ]] || { echo "ERROR: manifests not restored"; exit 1; }
[[ "$RESTORED_PLACEMENTS" -ge "3" ]] || { echo "ERROR: placements not restored"; exit 1; }

# Verify bytes pulled from tutor and re-distributed (the canonical event emitted by
# NodeFsRestoreService.handleByteFromTutor tras reconstruct + reUpload exitoso).
LOGS_REUPLOAD="$("$DOCKER_BIN" logs distributed-node-1 2>&1 | grep -c 'RESTORE_REUPLOAD_COMPLETED' || true)"
echo "  RESTORE_REUPLOAD_COMPLETED log entries: $LOGS_REUPLOAD"
[[ "$LOGS_REUPLOAD" -ge "1" ]] || { echo "ERROR: RESTORE re-upload did not complete"; exit 1; }

echo "==> [9/9] Login post-recovery + download via fallback a local blob"
LOGIN2_JSON="$TMP_DIR/login2.json"
curl -sS -o "$LOGIN2_JSON" -X POST "http://localhost:8081/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
TOKEN2="$(python3 -c "import json; print(json.load(open('$LOGIN2_JSON'))['token'])")"

curl -sS -o "$TMP_DIR/tree.json" "http://localhost:8081/fs/tree" -H "Authorization: Bearer $TOKEN2"
ENTRY_ID2="$(python3 -c "
import json
data = json.load(open('$TMP_DIR/tree.json'))
for e in data.get('entries', []):
    if 'bft-test' in e.get('path',''):
        print(e['entryId']); break
")"
[[ -n "$ENTRY_ID2" ]] || { echo "ERROR: file not visible in /fs/tree"; cat "$TMP_DIR/tree.json"; exit 1; }

DOWNLOADED="$TMP_DIR/downloaded.txt"
DL_CODE="$(curl -sS -o "$DOWNLOADED" -w '%{http_code}' \
  "http://localhost:8081/files/entries/$ENTRY_ID2/content" \
  -H "Authorization: Bearer $TOKEN2")"
[[ "$DL_CODE" == "200" ]] || { echo "ERROR: download HTTP $DL_CODE"; cat "$DOWNLOADED"; exit 1; }
cmp -s "$PAYLOAD" "$DOWNLOADED" || { echo "ERROR: downloaded bytes mismatch"; exit 1; }

cmp -s "$PAYLOAD" "$DOWNLOADED" || { echo "ERROR: downloaded bytes mismatch"; exit 1; }

# El download debe ir por el path canónico (reconstruct distribuido sobre el fileId
# nuevo del re-upload). El fallback local-blob ha sido eliminado en distribución activa, así que
# DOWNLOAD_FALLBACK_LOCAL_BLOB NO debe aparecer.
LOGS_FALLBACK="$("$DOCKER_BIN" logs distributed-node-1 2>&1 | grep -c 'DOWNLOAD_FALLBACK_LOCAL_BLOB' || true)"
echo "  DOWNLOAD_FALLBACK_LOCAL_BLOB log entries: $LOGS_FALLBACK (expected 0)"
[[ "$LOGS_FALLBACK" == "0" ]] || { echo "ERROR: blob fallback should NOT trigger"; exit 1; }

echo
# Tras el ACK por cada fragmentId del manifest viejo, recovery_orphan_fragment del
# tutor debe quedar VACÍO (cero rows huérfanos en estado estable).
LOGS_ACK="$("$DOCKER_BIN" logs distributed-node-1 2>&1 | grep -c 'RESTORE_REUPLOAD_ORPHAN_ACK' || true)"
ORPHAN_ROWS_AFTER="$("$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -t -A \
  -c "SELECT COUNT(*) FROM recovery_orphan_fragment;" 2>/dev/null | tr -d '[:space:]' || echo "?")"
echo "  RESTORE_REUPLOAD_ORPHAN_ACK log entries: $LOGS_ACK (expected ≥1)"
echo "  recovery_orphan_fragment rows on tutor post-ACK: $ORPHAN_ROWS_AFTER (expected 0)"
[[ "$LOGS_ACK" -ge "1" ]] || { echo "ERROR: RESTORE re-upload did not log orphan ACK summary"; exit 1; }
[[ "$ORPHAN_ROWS_AFTER" == "0" ]] || { echo "ERROR: orphan fragments not cleaned up post-ACK"; exit 1; }

echo "SUCCESS: end-to-end smoke passed (RESTORE re-uploads, no local blob, no orphans)"
echo "  - Migración SQL custody → recovery_orphan: OK"
echo "  - Bootstrap runner reconstruyó + re-emitió bytes: OK ($LOGS_REUPLOAD log entries)"
echo "  - Download via reconstruct distribuido (path canónico): OK"
echo "  - Cero blob fallback (modelo fragments-only nodes preservado): OK"
echo "  - Cero orphan fragments tutor-side post-ACK: OK"
echo "  - Bytes idénticos al original: OK"

if [[ "$KEEP_RUNNING" == "false" ]]; then
  "$DOCKER_BIN" compose down -v >/dev/null
fi
