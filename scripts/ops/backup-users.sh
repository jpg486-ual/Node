#!/usr/bin/env bash
# Vuelca la base de datos Postgres del nodo vía pg_dump.
#
# BACKUP_SCOPE=auth-fs (default) → solo schemas de autenticación + filesystem.
# BACKUP_SCOPE=full              → dump completo del schema de aplicación.
#
# Uso:
#   ./scripts/ops/backup-users.sh                                  # auth-fs
#   BACKUP_SCOPE=full ./scripts/ops/backup-users.sh
#   DATABASE_URL=postgresql://... ./scripts/ops/backup-users.sh
#
# Salidas:
#   exit 0       dump generado
#   exit !=0     pg_dump falló o BACKUP_SCOPE inválido
#   artifact     ${BACKUP_DIR}/node-<scope>-<timestamp>.dump

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_BACKUP_DIR="${SCRIPT_DIR}/../../backups"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

DATABASE_URL="${DATABASE_URL:-postgresql://node:node@localhost:5432/node}"
BACKUP_DIR="${BACKUP_DIR:-${DEFAULT_BACKUP_DIR}}"
BACKUP_SCOPE="${BACKUP_SCOPE:-auth-fs}" # auth-fs | full
BACKUP_FILE="${BACKUP_FILE:-${BACKUP_DIR}/node-${BACKUP_SCOPE}-${TIMESTAMP}.dump}"

mkdir -p "${BACKUP_DIR}"

echo "[backup-users] Starting backup"
echo "[backup-users] DATABASE_URL=${DATABASE_URL}"
echo "[backup-users] BACKUP_SCOPE=${BACKUP_SCOPE}"
echo "[backup-users] BACKUP_FILE=${BACKUP_FILE}"

if [[ "${BACKUP_SCOPE}" != "auth-fs" && "${BACKUP_SCOPE}" != "full" ]]; then
  echo "[backup-users] ERROR unsupported BACKUP_SCOPE=${BACKUP_SCOPE}"
  echo "[backup-users] Supported values: auth-fs | full"
  exit 1
fi

if [[ "${BACKUP_SCOPE}" == "full" ]]; then
  pg_dump \
    --format=custom \
    --file="${BACKUP_FILE}" \
    --dbname="${DATABASE_URL}"
else
  pg_dump \
    --format=custom \
    --file="${BACKUP_FILE}" \
    --dbname="${DATABASE_URL}" \
    --table=user_account \
    --table=registration_code \
    --table=user_session \
    --table=fs_entry
fi

echo "[backup-users] Backup created successfully: ${BACKUP_FILE}"
