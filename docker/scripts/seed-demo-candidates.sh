#!/usr/bin/env bash
set -euo pipefail

# Siembra de candidatos de discovery para la demo TFG del cluster de 3 nodos.
#
# Cada nodo de la red mantiene su propio directorio in-memory de candidatos
# (`DiscoveryCandidateDirectory`). Para que el flujo de subida de archivos
# (negotiation -> custodia distribuida) funcione, los 3 nodos deben aparecer
# como candidatos en los 3 directorios. Este script registra cada nodo
# (firmando con su propia clave privada) en los 3 directorios, asignandole
# su zona canonica del docker-compose:
#
#   node1 -> zone-a/rack-1
#   node2 -> zone-b/rack-1
#   node3 -> zone-c/rack-1
#
# Uso, tras `docker compose up -d`:
#
#   docker/scripts/seed-demo-candidates.sh
#
# Requisitos:
#   - claves criptograficas en docker/keys/, generables con
#     `docker/scripts/generate-node-keys.sh`.
#   - los 3 nodos en healthy en los puertos 8081, 8082 y 8083.

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
KEYS_DIR="$ROOT_DIR/docker/keys"

declare -a NODE_ZONES=(
  ""                  # placeholder index 0
  "zone-a/rack-1"     # node1
  "zone-b/rack-1"     # node2
  "zone-c/rack-1"     # node3
)

declare -a TARGET_URLS=(
  ""
  "http://localhost:8081"
  "http://localhost:8082"
  "http://localhost:8083"
)

# El supernodo persiste el baseUrl del candidato; el origen lo usa para resolver
# discoveredNodeId -> URL custodia sin consultar topology config. Las URLs internas
# docker (port 8080) son las que los peers usan para el flujo inter-nodo, no las
# externas (8081/8082/8083) que sirven al host.
declare -a INTERNAL_URLS=(
  ""
  "http://node1:8080"
  "http://node2:8080"
  "http://node3:8080"
)

signed_put_candidate() {
  local sender_idx="$1"
  local target_url="$2"

  local priv_der="$KEYS_DIR/node${sender_idx}-private.der"
  local pub_der="$KEYS_DIR/node${sender_idx}-public.der"
  if [[ ! -f "$priv_der" || ! -f "$pub_der" ]]; then
    echo "ERROR: claves no encontradas para node${sender_idx}" >&2
    echo "       ejecuta primero docker/scripts/generate-node-keys.sh" >&2
    return 1
  fi

  local node_hash
  node_hash=$(/usr/bin/shasum -a 256 "$pub_der" | /usr/bin/awk '{print $1}')
  local node_id="node-${node_hash:0:24}"
  local zone="${NODE_ZONES[$sender_idx]}"

  local method="PUT"
  local request_path="/ops/discovery/candidates/${node_id}"
  local timestamp
  timestamp="$(/bin/date +%s)"
  local nonce="seed-${sender_idx}-${target_url##*:}-${timestamp}-${RANDOM}"
  local query=""

  local payload_file canonical_file key_pem_file signature_bin_file
  payload_file=$(/usr/bin/mktemp)
  canonical_file=$(/usr/bin/mktemp)
  key_pem_file=$(/usr/bin/mktemp)
  signature_bin_file=$(/usr/bin/mktemp)

  local self_base_url="${INTERNAL_URLS[$sender_idx]}"
  /bin/cat > "$payload_file" <<JSON
{
  "failureDomain": "$zone",
  "baseUrl": "$self_base_url",
  "originalRequestedBucket": 1024,
  "acceptedBuckets": [1024, 2048, 4096]
}
JSON

  /usr/bin/printf "%s\n%s\n%s\n%s\n%s" \
    "$method" "$request_path" "$query" "$nonce" "$timestamp" > "$canonical_file"
  /usr/bin/openssl pkey -inform DER -in "$priv_der" -out "$key_pem_file" \
    >/dev/null 2>&1
  /usr/bin/openssl dgst -sha256 -sign "$key_pem_file" \
    -out "$signature_bin_file" "$canonical_file"
  local signature_b64
  signature_b64=$(/usr/bin/base64 < "$signature_bin_file" | /usr/bin/tr -d '\n')

  /bin/echo ">>> seed: node${sender_idx} (zone=${zone}) -> ${target_url}${request_path}"
  /usr/bin/curl -fsS -X "$method" "${target_url}${request_path}" \
    -H "Content-Type: application/json" \
    -H "X-Node-Id: $node_id" \
    -H "X-Nonce: $nonce" \
    -H "X-Timestamp: $timestamp" \
    -H "X-Signature-Algorithm: SHA256withECDSA" \
    -H "X-Signature: $signature_b64" \
    --data @"$payload_file" >/dev/null
  /bin/echo "    OK"

  /bin/rm -f "$payload_file" "$canonical_file" "$key_pem_file" "$signature_bin_file"
}

/bin/echo "==> Sembrando 3 nodos x 3 directorios = 9 upserts firmados"
for sender in 1 2 3; do
  for target in 1 2 3; do
    signed_put_candidate "$sender" "${TARGET_URLS[$target]}"
  done
done

/bin/echo
/bin/echo "==> Cluster de demo sembrado:"
/bin/echo "    node1 (zone-a/rack-1), node2 (zone-b/rack-1), node3 (zone-c/rack-1)"
/bin/echo "    cada nodo aparece ahora como candidato en los 3 directorios."
