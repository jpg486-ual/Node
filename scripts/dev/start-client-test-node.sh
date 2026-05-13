#!/usr/bin/env bash
# Bootstrapea un nodo aislado para tests de cliente (H2 en memoria o Postgres).
#
# Genera material de identidad si falta, desactiva features no requeridas
# (discovery, negotiation, custody, recovery), arranca el nodo en background y
# escribe logs a $NODE_LOG_FILE. Diseñado como prerequisito para los smokes
# de cliente que asumen un nodo escuchando en NODE_PORT.
#
# Uso:
#   ./scripts/dev/start-client-test-node.sh                # Postgres via docker
#   USE_H2=true ./scripts/dev/start-client-test-node.sh    # H2 in-memory
#   NODE_PORT=8090 ./scripts/dev/start-client-test-node.sh
#
# Salidas:
#   exit 0          nodo arrancado y health endpoint respondiendo
#   exit !=0        bootstrap o health check falló
#   artifacts       logs/dev-client-node.log + (H2) target/dev-client-node-h2/

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

TEST_USERNAME="${TEST_USERNAME:-tfg_client_test}"
TEST_PASSWORD="${TEST_PASSWORD:-NodeClient2026!}"
TEST_QUOTA_MB="${TEST_QUOTA_MB:-2048}"
NODE_PORT="${NODE_PORT:-8081}"
NODE_BASE_URL="http://localhost:${NODE_PORT}"
NODE_LOG_FILE="${NODE_LOG_FILE:-$ROOT_DIR/logs/dev-client-node.log}"
USE_H2="${USE_H2:-false}"
H2_DB_FILE="${H2_DB_FILE:-$ROOT_DIR/target/dev-client-node-h2/node}"
NODE_FEATURE_FLAGS="--node.features.discovery-enabled=false --node.features.negotiation-enabled=false --node.features.custody-enabled=false --node.features.recovery-enabled=false --node.discovery.retry.enabled=false"

if [[ "$USE_H2" != "true" ]]; then
  if command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
  else
    COMPOSE_CMD=(docker compose)
  fi
fi

mkdir -p "$(dirname "$NODE_LOG_FILE")"

ensure_identity_material() {
  local env_file="$ROOT_DIR/docker/env/node1.env"
  if [[ -f "$env_file" ]]; then
    return 0
  fi

  local generator="$ROOT_DIR/docker/scripts/generate-node-keys.sh"
  if [[ ! -x "$generator" ]]; then
    chmod +x "$generator"
  fi

  echo "No existe $env_file. Generando material de identidad docker/env..."
  "$generator" >/dev/null
}

wait_for_http() {
  local retries=60
  local delay=1
  local url="$1"
  for _ in $(seq 1 "$retries"); do
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)"
    if [[ "$code" == "401" || "$code" == "200" ]]; then
      return 0
    fi
    sleep "$delay"
  done
  return 1
}

is_node_ready() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "$NODE_BASE_URL/auth/me" || true)"
  [[ "$code" == "401" ]]
}

login_and_print_token() {
  local code
  code="$(curl -s -o /tmp/node_test_login.json -w '%{http_code}' -X POST "$NODE_BASE_URL/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}")"

  if [[ "$code" != "200" ]]; then
    return 1
  fi

  local token
  token="$(python3 -c "import json; print(json.load(open('/tmp/node_test_login.json'))['token'])")"

  echo
  echo "Node listo en: $NODE_BASE_URL"
  echo "Usuario test: $TEST_USERNAME"
  echo "Password test: $TEST_PASSWORD"
  echo "Token (session): $token"
  echo "Guarda en NodeClient UserDefaults: node.baseURL=$NODE_BASE_URL, node.sessionToken=<token>"
  return 0
}

extract_invitation_code() {
  local issue_output="$1"
  printf '%s\n' "$issue_output" \
    | sed -nE 's/.*Registration code generated:[[:space:]]*([A-Za-z0-9_-]+).*/\1/p' \
    | tail -n1
}

h2_datasource_url() {
  printf 'jdbc:h2:file:%s;MODE=PostgreSQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1' "$H2_DB_FILE"
}

issue_registration_code_h2() {
  local h2_url
  h2_url="$(h2_datasource_url)"
  ensure_identity_material
  set -a
  source "$ROOT_DIR/docker/env/node1.env"
  set +a
  # --management.server.port=0 evita colision con el nodo background ya
  # corriendo (que ocupa el management port por la default de application.properties).
  # 0 = puerto random; este JVM solo emite la code y sale
  # (console-exit-after-issue=true), no necesita actuator estable.
  printf '%s\n' "$TEST_QUOTA_MB" | "$ROOT_DIR/mvnw" -q spring-boot:run \
    "-Dspring-boot.run.arguments=--node.persistence.mode=postgres --spring.datasource.url=$h2_url --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= --node.user-registration.console-issue-enabled=true --node.user-registration.console-exit-after-issue=true --server.port=18081 --management.server.port=0 $NODE_FEATURE_FLAGS"
}

start_node_h2() {
  local h2_url
  h2_url="$(h2_datasource_url)"
  mkdir -p "$(dirname "$H2_DB_FILE")"
  mkdir -p "$ROOT_DIR/target/test-client-files" "$ROOT_DIR/target/test-client-files-staging"

  ensure_identity_material
  set -a
  source "$ROOT_DIR/docker/env/node1.env"
  set +a
  nohup "$ROOT_DIR/mvnw" -q spring-boot:run \
    "-Dspring-boot.run.arguments=--node.persistence.mode=postgres --spring.datasource.url=$h2_url --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= --server.port=$NODE_PORT --node.client-files.base-directory=$ROOT_DIR/target/test-client-files --node.client-files.staging-directory=$ROOT_DIR/target/test-client-files-staging $NODE_FEATURE_FLAGS" \
    >"$NODE_LOG_FILE" 2>&1 &
}

issue_registration_code_postgres() {
  ensure_identity_material
  # --management.server.port=0: ver nota en issue_registration_code_h2.
  printf '%s\n' "$TEST_QUOTA_MB" | bash -lc "
    set -euo pipefail
    source '$ROOT_DIR/docker/env/node1.env'
    '$ROOT_DIR/mvnw' -q spring-boot:run \
      -Dspring-boot.run.arguments='--node.persistence.mode=postgres --spring.datasource.url=jdbc:postgresql://localhost:5433/node --spring.datasource.driver-class-name=org.postgresql.Driver --spring.datasource.username=node --spring.datasource.password=node --node.user-registration.console-issue-enabled=true --node.user-registration.console-exit-after-issue=true --server.port=18081 --management.server.port=0 --node.discovery.retry.enabled=false'
  " 2>&1
}

register_user() {
  local invitation_code="$1"
  local register_code
  register_code="$(curl -s -o /tmp/node_test_register.json -w '%{http_code}' -X POST "$NODE_BASE_URL/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"invitationCode\":\"$invitation_code\",\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}")"

  if [[ "$register_code" != "201" ]]; then
    echo "Registro falló con código HTTP: $register_code"
    cat /tmp/node_test_register.json || true
    return 1
  fi
}

if [[ "$USE_H2" != "true" ]]; then
  "${COMPOSE_CMD[@]}" up -d postgres-node1 >/dev/null
fi

if ! is_node_ready; then
  echo "Arrancando nodo en background (logs: $NODE_LOG_FILE)"
  if [[ "$USE_H2" == "true" ]]; then
    start_node_h2
  else
    ensure_identity_material
    nohup bash -lc "
      set -euo pipefail
      source '$ROOT_DIR/docker/env/node1.env'
      '$ROOT_DIR/mvnw' -q spring-boot:run \
        -Dspring-boot.run.arguments='--node.persistence.mode=postgres --spring.datasource.url=jdbc:postgresql://localhost:5433/node --spring.datasource.driver-class-name=org.postgresql.Driver --spring.datasource.username=node --spring.datasource.password=node --server.port=$NODE_PORT --node.discovery.retry.enabled=false'
    " >"$NODE_LOG_FILE" 2>&1 &
  fi
fi

if ! wait_for_http "$NODE_BASE_URL/auth/me"; then
  echo "No se pudo arrancar el nodo en $NODE_BASE_URL"
  echo "Revisa logs: $NODE_LOG_FILE"
  exit 1
fi

if login_and_print_token; then
  exit 0
fi

echo "Usuario test no encontrado. Generando invitation code persistente..."
if [[ "$USE_H2" == "true" ]]; then
  ISSUE_OUT="$(issue_registration_code_h2 2>&1)"
else
  ISSUE_OUT="$(issue_registration_code_postgres)"
fi

INV_CODE="$(extract_invitation_code "$ISSUE_OUT")"

if [[ -z "$INV_CODE" ]]; then
  echo "No se pudo extraer el invitation code"
  printf '%s\n' "$ISSUE_OUT"
  exit 1
fi

if ! register_user "$INV_CODE"; then
  exit 1
fi

if ! login_and_print_token; then
  echo "No se pudo hacer login tras registrar usuario"
  exit 1
fi
