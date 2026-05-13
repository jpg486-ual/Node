#!/usr/bin/env bash
# Genera 3 pares ECDSA P-256 (DER + base64), los hashes node-<24hex> derivados, y
# escribe docker/env/node{1,2,3}.env con las propiedades de identidad + whitelist
# `tutorAcceptedPublicKeys` / `acceptedFragmentSenderKeys`. Sincroniza también el
# `SPRING_APPLICATION_JSON.custody-liveness.remote-base-urls` del docker-compose.yml
# para que los hashes nuevos resuelvan a las URLs internas docker (node{1,2,3}:8080).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
KEYS_DIR="$ROOT_DIR/docker/keys"
ENV_DIR="$ROOT_DIR/docker/env"

mkdir -p "$KEYS_DIR" "$ENV_DIR"

for n in 1 2 3; do
  openssl ecparam -name prime256v1 -genkey -noout -out "$KEYS_DIR/node${n}-raw.pem"
  openssl pkcs8 -topk8 -nocrypt -in "$KEYS_DIR/node${n}-raw.pem" -outform DER -out "$KEYS_DIR/node${n}-private.der"
  openssl ec -in "$KEYS_DIR/node${n}-raw.pem" -pubout -outform DER -out "$KEYS_DIR/node${n}-public.der" >/dev/null 2>&1

  base64 < "$KEYS_DIR/node${n}-private.der" | tr -d '\n' > "$KEYS_DIR/node${n}-private.b64"
  base64 < "$KEYS_DIR/node${n}-public.der" | tr -d '\n' > "$KEYS_DIR/node${n}-public.b64"

  hash=$(shasum -a 256 "$KEYS_DIR/node${n}-public.der" | awk '{print $1}')
  echo "node-${hash:0:24}" > "$KEYS_DIR/node${n}-id.txt"
done

PUB1=$(cat "$KEYS_DIR/node1-public.b64")
PUB2=$(cat "$KEYS_DIR/node2-public.b64")
PUB3=$(cat "$KEYS_DIR/node3-public.b64")

ALL_TRUSTED="$PUB1,$PUB2,$PUB3"

for n in 1 2 3; do
  PRIV=$(cat "$KEYS_DIR/node${n}-private.b64")
  PUB=$(cat "$KEYS_DIR/node${n}-public.b64")
  cat > "$ENV_DIR/node${n}.env" <<EOF
NODE_IDENTITY_PUBLIC_KEY_BASE64=$PUB
NODE_IDENTITY_PRIVATE_KEY_BASE64=$PRIV
NODE_IDENTITY_KEY_ALGORITHM=EC
NODE_IDENTITY_TRUSTED_PUBLIC_KEYS=$ALL_TRUSTED
NODE_TOPOLOGY_TUTOR_ACCEPTED_PUBLIC_KEYS=$ALL_TRUSTED
NODE_TOPOLOGY_ACCEPTED_FRAGMENT_SENDER_KEYS=$ALL_TRUSTED
EOF

done

echo "Generated keys in: $KEYS_DIR"
echo "Generated env files in: $ENV_DIR"
for n in 1 2 3; do
  echo "node${n} id: $(cat "$KEYS_DIR/node${n}-id.txt")"
done

# Sincronizar docker-compose.yml SPRING_APPLICATION_JSON con los nuevos hashes.
#
# Las claves ECDSA son no determinísticas: cada ejecución genera hashes nuevos. El
# `SPRING_APPLICATION_JSON.custody-liveness.remote-base-urls` del compose mapea
# nodeId(hash) → baseUrl. Si los hashes del compose y los actuales divergen,
# custody-liveness no resuelve peers → escalation espuria + smokes en CI rojos.
#
# Este sync evita el desync silencioso:
#   - LOCAL: regenera keys + actualiza compose en una sola operación.
#   - CI: cada run regenera fresh + el compose queda coherente para los smokes
#     que arrancan después.

NODE1_ID=$(cat "$KEYS_DIR/node1-id.txt")
NODE2_ID=$(cat "$KEYS_DIR/node2-id.txt")
NODE3_ID=$(cat "$KEYS_DIR/node3-id.txt")

COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "WARNING: $COMPOSE_FILE not found; skipping compose sync"
else
  python3 - "$COMPOSE_FILE" "$NODE1_ID" "$NODE2_ID" "$NODE3_ID" <<'PY'
import re
import sys

compose_path, n1, n2, n3 = sys.argv[1:5]
new_json = (
    '{"node":{"custody-liveness":{"remote-base-urls":{'
    f'"{n1}":"http://node1:8080",'
    f'"{n2}":"http://node2:8080",'
    f'"{n3}":"http://node3:8080"'
    '}}}}'
)

with open(compose_path, "r") as f:
    content = f.read()

# Matcher: cualquier línea SPRING_APPLICATION_JSON con remote-base-urls (3 ocurrencias en el compose).
pattern = re.compile(
    r"^(?P<indent>\s+)SPRING_APPLICATION_JSON:\s*'\{[^']*remote-base-urls[^']*\}'",
    re.MULTILINE,
)

replacements = 0
def replace(match):
    global replacements
    replacements += 1
    return f"{match.group('indent')}SPRING_APPLICATION_JSON: '{new_json}'"

new_content = pattern.sub(replace, content)
if replacements != 3:
    print(f"ERROR: expected 3 SPRING_APPLICATION_JSON replacements, did {replacements}")
    sys.exit(1)

with open(compose_path, "w") as f:
    f.write(new_content)

print(f"docker-compose.yml synced: {replacements} SPRING_APPLICATION_JSON entries updated")
PY
fi
