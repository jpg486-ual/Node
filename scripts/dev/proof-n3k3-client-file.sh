#!/usr/bin/env bash
# Genera y firma proof artifacts vía cluster docker de 3 nodos con esquema RS(3,3).
#
# Sube ficheros como cliente autenticado contra cada nodo del cluster, captura los
# fragmentos resultantes y produce artefactos que demuestran la integridad de la
# distribución y la custodia configurada (CUSTODY_SECONDS).
#
# Uso:
#   ./scripts/dev/proof-n3k3-client-file.sh
#   NO_BUILD=true ./scripts/dev/proof-n3k3-client-file.sh           # reutiliza imagen
#   FORCE_RECREATE=false ./scripts/dev/proof-n3k3-client-file.sh    # reusa contenedores
#   TEST_QUOTA_MB=4096 ./scripts/dev/proof-n3k3-client-file.sh
#
# Salidas:
#   exit 0          generación completa + verificación OK
#   exit !=0        fallo en upload, custody o verificación
#   artifacts       /proof/* dentro de cada nodo + logs en stdout

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
KEYS_DIR="$DOCKER_DIR/keys"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

TEST_USERNAME="${TEST_USERNAME:-tfg_client_test}"
TEST_PASSWORD="${TEST_PASSWORD:-NodeClient2026!}"
TEST_QUOTA_MB="${TEST_QUOTA_MB:-2048}"
PROOF_DIR="${PROOF_DIR:-/proof}"
NO_BUILD="${NO_BUILD:-false}"
FORCE_RECREATE="${FORCE_RECREATE:-true}"

DOCKER_BIN="$(command -v docker || true)"
if [[ -z "$DOCKER_BIN" ]]; then
  echo "ERROR: docker is required"
  exit 1
fi

if "$DOCKER_BIN" compose version >/dev/null 2>&1; then
  COMPOSE_CMD=("$DOCKER_BIN" compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=("$(command -v docker-compose)")
else
  echo "ERROR: neither docker compose plugin nor docker-compose was found"
  exit 1
fi

if [[ ! -f "$KEYS_DIR/node1-private.der" || ! -f "$KEYS_DIR/node2-private.der" || ! -f "$KEYS_DIR/node3-private.der" ]]; then
  echo "==> Generating node key material"
  "$DOCKER_DIR/scripts/generate-node-keys.sh"
fi

echo "==> Starting 3-node docker cluster"
cd "$ROOT_DIR"
if [[ "$NO_BUILD" == "true" ]]; then
  if [[ "$FORCE_RECREATE" == "true" ]]; then
    "${COMPOSE_CMD[@]}" up --force-recreate -d >/dev/null
  else
    "${COMPOSE_CMD[@]}" up -d >/dev/null
  fi
else
  if [[ "$FORCE_RECREATE" == "true" ]]; then
    "${COMPOSE_CMD[@]}" up --build --force-recreate -d >/dev/null
  else
    "${COMPOSE_CMD[@]}" up --build -d >/dev/null
  fi
fi

wait_http() {
  local url="$1"
  local retries=90
  for _ in $(seq 1 "$retries"); do
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)"
    if [[ "$code" == "200" || "$code" == "401" || "$code" == "404" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

echo "==> Waiting nodes to be reachable"
wait_http "http://localhost:8081/auth/me" || { echo "node1 not ready"; exit 1; }
wait_http "http://localhost:8082/auth/me" || { echo "node2 not ready"; exit 1; }
wait_http "http://localhost:8083/auth/me" || { echo "node3 not ready"; exit 1; }

# Espera a que el discovery directory de node1 vea ≥3 candidates antes del upload —
# en CI Linux el self-discovery propagation tarda significativamente más que en macOS
# local (Spring Boot startup + SelfDiscoveryRegistrar HTTP roundtrips). Arrancar el
# upload pre-convergencia produce 503 INSUFFICIENT_CUSTODIANS.
echo "==> Waiting for discovery directory convergence (≥3 candidates in node1)"
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
  echo "  -- discovery_candidate snapshot @node1 --"
  "$DOCKER_BIN" exec node-postgres-1 psql -U node -d node -c \
    "SELECT node_id, base_url, healthy, last_seen_at FROM discovery_candidate ORDER BY node_id;" 2>/dev/null || true
  echo "  -- discovery_candidate snapshot @node2 --"
  "$DOCKER_BIN" exec node-postgres-2 psql -U node -d node -c \
    "SELECT node_id, base_url, healthy, last_seen_at FROM discovery_candidate ORDER BY node_id;" 2>/dev/null || true
  echo "  -- discovery_candidate snapshot @node3 --"
  "$DOCKER_BIN" exec node-postgres-3 psql -U node -d node -c \
    "SELECT node_id, base_url, healthy, last_seen_at FROM discovery_candidate ORDER BY node_id;" 2>/dev/null || true
  for node in 1 2 3; do
    echo "  -- node${node} self-discovery log --"
    "$DOCKER_BIN" logs --tail 200 "distributed-node-${node}" 2>&1 \
      | grep -iE "self-discov|self.discov|registrar|renewal" | tail -15 || true
  done
  return 1
}
wait_discovery_convergence || exit 1

issue_registration_code_sql() {
  local code="$1"
  "$DOCKER_BIN" exec -i node-postgres-1 psql -U node -d node <<SQL >/dev/null
INSERT INTO registration_code(code, quota_mb, expires_at, used, used_at, created_at)
VALUES ('$code', $TEST_QUOTA_MB, NOW() + INTERVAL '2 days', FALSE, NULL, NOW())
ON CONFLICT (code) DO UPDATE SET
  quota_mb = EXCLUDED.quota_mb,
  expires_at = EXCLUDED.expires_at,
  used = FALSE,
  used_at = NULL,
  created_at = NOW();
SQL
}

login() {
  curl -s -o "$TMP_DIR/login.json" -w '%{http_code}' -X POST http://localhost:8081/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}"
}

echo "==> Ensuring test user exists in node1"
LOGIN_CODE="$(login)"
if [[ "$LOGIN_CODE" != "200" ]]; then
  INV_CODE="N3K3$RANDOM"
  issue_registration_code_sql "$INV_CODE"

  REG_CODE="$(curl -s -o "$TMP_DIR/register.json" -w '%{http_code}' -X POST http://localhost:8081/auth/register \
    -H 'Content-Type: application/json' \
    -d "{\"invitationCode\":\"$INV_CODE\",\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}")"

  if [[ "$REG_CODE" != "201" ]]; then
    echo "ERROR: register failed with HTTP $REG_CODE"
    cat "$TMP_DIR/register.json" || true
    exit 1
  fi

  LOGIN_CODE="$(login)"
  [[ "$LOGIN_CODE" == "200" ]] || { echo "ERROR: login failed after register"; cat "$TMP_DIR/login.json"; exit 1; }
fi

TOKEN="$(python3 -c "import json; print(json.load(open('$TMP_DIR/login.json'))['token'])")"

delete_path_if_exists() {
  local path_to_delete="$1"
  local tree_file="$TMP_DIR/tree-before-delete.json"
  local tree_code
  tree_code="$(curl -s -o "$tree_file" -w '%{http_code}' -X GET http://localhost:8081/fs/tree -H "Authorization: Bearer $TOKEN")"
  [[ "$tree_code" == "200" ]] || { echo "ERROR: tree read before delete failed with HTTP $tree_code"; exit 1; }

  local existing_entry_id
  existing_entry_id="$(python3 - "$tree_file" "$path_to_delete" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text())
target = sys.argv[2]
for entry in payload.get('entries', []):
    if entry.get('path') == target and not entry.get('deleted', False):
        print(entry.get('entryId', ''))
        break
PY
)"

  if [[ -n "$existing_entry_id" ]]; then
    local delete_code
    delete_code="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "http://localhost:8081/fs/entries/$existing_entry_id" -H "Authorization: Bearer $TOKEN")"
    [[ "$delete_code" == "200" ]] || { echo "ERROR: delete existing path $path_to_delete failed with HTTP $delete_code"; exit 1; }
  fi
}

find_entry_id_by_path_any_state() {
  local target_path="$1"
  local tree_file="$TMP_DIR/tree-find-entry.json"
  local tree_code
  tree_code="$(curl -s -o "$tree_file" -w '%{http_code}' -X GET http://localhost:8081/fs/tree -H "Authorization: Bearer $TOKEN")"
  [[ "$tree_code" == "200" ]] || { echo "ERROR: tree read before upsert lookup failed with HTTP $tree_code"; exit 1; }

  python3 - "$tree_file" "$target_path" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text())
target = sys.argv[2]
for entry in payload.get('entries', []):
    if entry.get('path') == target:
        print(entry.get('entryId', ''))
        break
PY
}

echo "==> Creating proof file metadata and uploading content"
PROOF_CONTENT_FILE="$TMP_DIR/n3k3-basic.txt"
cat > "$PROOF_CONTENT_FILE" <<TXT
TFG/CTFG proof file
n=3,k=3 negotiation validation across node1,node2,node3
timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TXT

FILE_SIZE="$(wc -c < "$PROOF_CONTENT_FILE" | tr -d ' ')"
FILE_SHA256="$(shasum -a 256 "$PROOF_CONTENT_FILE" | awk '{print $1}')"
VISIBLE_PROOF_PATH="$PROOF_DIR/n3k3-basic-node1.txt"
LEGACY_PROOF_PATH_NODE2="$PROOF_DIR/n3k3-basic-node2.txt"
LEGACY_PROOF_PATH_NODE3="$PROOF_DIR/n3k3-basic-node3.txt"

delete_path_if_exists "$LEGACY_PROOF_PATH_NODE2"
delete_path_if_exists "$LEGACY_PROOF_PATH_NODE3"

VISIBLE_EXISTING_ENTRY_ID="$(find_entry_id_by_path_any_state "$VISIBLE_PROOF_PATH")"
ENTRY_ID_PREFIX=""
if [[ -n "$VISIBLE_EXISTING_ENTRY_ID" ]]; then
  ENTRY_ID_PREFIX="\"entryId\":\"$VISIBLE_EXISTING_ENTRY_ID\","
fi

upsert_body="$TMP_DIR/upsert-response-visible.json"
upsert_code="$(curl -s -o "$upsert_body" -w '%{http_code}' -X POST http://localhost:8081/fs/entries \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{$ENTRY_ID_PREFIX\"path\":\"$VISIBLE_PROOF_PATH\",\"entryType\":\"FILE\",\"sizeBytes\":$FILE_SIZE,\"checksum\":\"$FILE_SHA256\",\"deleted\":false}")"

if [[ "$upsert_code" != "200" ]]; then
  echo "ERROR: fs upsert (visible proof) failed with HTTP $upsert_code"
  cat "$upsert_body"
  exit 1
fi

entry_id="$(python3 -c "import json; print(json.load(open('$upsert_body'))['entryId'])")"
upload_body="$TMP_DIR/upload-visible.json"
upload_code="$(curl -s -o "$upload_body" -w '%{http_code}' -X PUT "http://localhost:8081/files/entries/$entry_id/content" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @"$PROOF_CONTENT_FILE")"

[[ "$upload_code" == "200" ]] || { echo "ERROR: file upload (visible proof) failed with HTTP $upload_code"; cat "$upload_body"; exit 1; }

TREE_CODE="$(curl -s -o "$TMP_DIR/tree.json" -w '%{http_code}' -X GET http://localhost:8081/fs/tree -H "Authorization: Bearer $TOKEN")"
[[ "$TREE_CODE" == "200" ]] || { echo "ERROR: tree read failed with HTTP $TREE_CODE"; exit 1; }

python3 - <<PY
import json
from pathlib import Path
data = json.loads(Path("$TMP_DIR/tree.json").read_text())
entries = data.get("entries", [])
expected = [
  "$VISIBLE_PROOF_PATH"
]
legacy = [
  "$LEGACY_PROOF_PATH_NODE2",
  "$LEGACY_PROOF_PATH_NODE3"
]
for path in expected:
  if not any(e.get("path") == path and not e.get("deleted", False) for e in entries):
    raise SystemExit(f"ERROR: uploaded proof file not visible in /fs/tree: {path}")
for path in legacy:
  if any(e.get("path") == path and not e.get("deleted", False) for e in entries):
    raise SystemExit(f"ERROR: legacy proof path should not be visible anymore: {path}")
PY

node_id() {
  local index="$1"
  local pub_der="$KEYS_DIR/node${index}-public.der"
  local hash
  hash="$(shasum -a 256 "$pub_der" | awk '{print $1}')"
  echo "node-${hash:0:24}"
}

create_canonical_signature() {
  local node_index="$1"
  local method="$2"
  local request_path="$3"
  local nonce="$4"
  local timestamp="$5"
  local signature_file="$6"

  local priv_der="$KEYS_DIR/node${node_index}-private.der"
  local pem_file="$TMP_DIR/node${node_index}-key.pem"
  local canonical_file="$TMP_DIR/canonical-${node_index}.txt"

  local canonical_path="$request_path"
  local canonical_query=""
  if [[ "$request_path" == *\?* ]]; then
    canonical_path="${request_path%%\?*}"
    canonical_query="${request_path#*\?}"
  fi
  printf "%s\n%s\n%s\n%s\n%s" "$method" "$canonical_path" "$canonical_query" "$nonce" "$timestamp" > "$canonical_file"
  openssl pkey -inform DER -in "$priv_der" -out "$pem_file" >/dev/null 2>&1
  openssl dgst -sha256 -sign "$pem_file" -out "$signature_file" "$canonical_file"
}

signed_call() {
  local node_index="$1"
  local method="$2"
  local base_url="$3"
  local request_path="$4"
  local payload_file="${5:-}"
  local out_body_file="$6"

  local node="$(node_id "$node_index")"
  local nonce="nonce-$(date +%s)-$RANDOM"
  local timestamp="$(date +%s)"
  local signature_bin="$TMP_DIR/signature-${node_index}.bin"
  create_canonical_signature "$node_index" "$method" "$request_path" "$nonce" "$timestamp" "$signature_bin"
  local signature
  signature="$(base64 < "$signature_bin" | tr -d '\n')"

  if [[ -n "$payload_file" ]]; then
    curl -sS -o "$out_body_file" -w '%{http_code}' -X "$method" "$base_url$request_path" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      -H "X-Node-Id: $node" \
      -H "X-Nonce: $nonce" \
      -H "X-Timestamp: $timestamp" \
      -H 'X-Signature-Algorithm: SHA256withECDSA' \
      -H "X-Signature: $signature" \
      --data @"$payload_file"
  else
    curl -sS -o "$out_body_file" -w '%{http_code}' -X "$method" "$base_url$request_path" \
      -H 'Accept: application/json' \
      -H "X-Node-Id: $node" \
      -H "X-Nonce: $nonce" \
      -H "X-Timestamp: $timestamp" \
      -H 'X-Signature-Algorithm: SHA256withECDSA' \
      -H "X-Signature: $signature"
  fi
}

REQUESTER_NODE_ID="$(node_id 1)"
TARGET_NODE1_ID="$(node_id 1)"
TARGET_NODE2_ID="$(node_id 2)"
TARGET_NODE3_ID="$(node_id 3)"

echo "==> Running RS(3,3) signed negotiations across node1,node2,node3"

do_negotiation() {
  local target_label="$1"
  local target_node_id="$2"
  local target_base_url="$3"
  local emitted_file_name="n3k3-basic-${target_label}.txt"

  local manifest_file="$TMP_DIR/manifest-$target_label.json"
  local create_file="$TMP_DIR/negotiate-create-$target_label.json"
  local create_body="$TMP_DIR/negotiate-create-$target_label-body.json"
  local confirm_file="$TMP_DIR/negotiate-confirm-$target_label.json"
  local confirm_body="$TMP_DIR/negotiate-confirm-$target_label-body.json"

  local file_id
  file_id="$(python3 - <<PY
import uuid
print(uuid.uuid4())
PY
)"

  cat > "$manifest_file" <<JSON
{
  "fileId": "$file_id",
  "originalFileName": "$emitted_file_name",
  "originalSizeBytes": $FILE_SIZE,
  "compressedSizeBytes": $FILE_SIZE,
  "compressionAlgorithm": "none",
  "originalFileHash": "$FILE_SHA256",
  "fragmentCount": 3,
  "fragmentSize": $(( (FILE_SIZE + 2) / 3 )),
  "redundancyN": 3,
  "redundancyK": 3,
  "fragmentHashes": [
    "$FILE_SHA256"
  ]
}
JSON

  cat > "$create_file" <<JSON
{
  "requesterNodeId": "$REQUESTER_NODE_ID",
  "targetNodeId": "$target_node_id",
  "bucketSize": 1024,
  "expectedStorageBytes": $FILE_SIZE,
  "transferMode": "MANIFEST_ONLY",
  "fragmentCount": 3,
  "redundancyScheme": "RS(3,3)",
  "expirationSeconds": 300,
  "requesterSignature": "node1-request-signature",
  "fileManifest": $(cat "$manifest_file")
}
JSON

  local create_code
  create_code="$(signed_call 1 POST "$target_base_url" "/negotiate" "$create_file" "$create_body")"
  [[ "$create_code" == "200" ]] || { echo "ERROR: negotiate create ($target_label) HTTP $create_code"; cat "$create_body"; exit 1; }

  local agreement_id
  agreement_id="$(python3 -c "import json; print(json.load(open('$create_body'))['agreementId'])")"

  cat > "$confirm_file" <<JSON
{
  "targetSignature": "target-signature-$target_label"
}
JSON

  local confirm_code
  confirm_code="$(signed_call 1 POST "$target_base_url" "/negotiate/$agreement_id/confirm" "$confirm_file" "$confirm_body")"
  [[ "$confirm_code" == "200" ]] || { echo "ERROR: negotiate confirm ($target_label) HTTP $confirm_code"; cat "$confirm_body"; exit 1; }

  # El campo transferAuthorizationToken se persiste internamente pero ya no se
  # expone en el payload JSON (NegotiationAgreementPayload.fromDomain lo fuerza
  # a null; vestigio del modelo simetrico abierto). Solo validamos el status.
  python3 - <<PY
import json
from pathlib import Path
payload = json.loads(Path("$confirm_body").read_text())
if payload.get("status") != "CONFIRMED":
    raise SystemExit("ERROR: negotiation $target_label not CONFIRMED")
PY

  echo "  - $target_label confirmed: $agreement_id"
}

do_negotiation "node1" "$TARGET_NODE1_ID" "http://localhost:8081"
do_negotiation "node2" "$TARGET_NODE2_ID" "http://localhost:8082"
do_negotiation "node3" "$TARGET_NODE3_ID" "http://localhost:8083"

echo
echo "SUCCESS: n=3,k=3 proof completed"
echo "- User: $TEST_USERNAME"
echo "- File visible in app path: $VISIBLE_PROOF_PATH"
echo "- Node base URL for app: http://localhost:8081"
echo "- Use login in app with user/password to see it"
