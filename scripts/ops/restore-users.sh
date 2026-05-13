#!/usr/bin/env bash
# Restaura un dump pg_restore (creado por backup-users.sh) sobre la base de datos
# Postgres del nodo. Por defecto revoca sesiones activas tras el restore.
#
# Uso:
#   BACKUP_FILE=/path/to/node-*.dump ./scripts/ops/restore-users.sh
#   DATABASE_URL=postgresql://... BACKUP_FILE=... ./scripts/ops/restore-users.sh
#   REVOKE_SESSIONS_AFTER_RESTORE=false BACKUP_FILE=... ./scripts/ops/restore-users.sh
#
# Salidas:
#   exit 0       restore + revocación completados
#   exit !=0    BACKUP_FILE ausente/inexistente o pg_restore falló

set -euo pipefail

DATABASE_URL="${DATABASE_URL:-postgresql://node:node@localhost:5432/node}"
BACKUP_FILE="${BACKUP_FILE:-}"
REVOKE_SESSIONS_AFTER_RESTORE="${REVOKE_SESSIONS_AFTER_RESTORE:-true}"

if [[ -z "${BACKUP_FILE}" ]]; then
  echo "[restore-users] ERROR: BACKUP_FILE is required"
  echo "Usage: BACKUP_FILE=/path/to/backup.dump DATABASE_URL=postgresql://... scripts/ops/restore-users.sh"
  exit 1
fi

if [[ ! -f "${BACKUP_FILE}" ]]; then
  echo "[restore-users] ERROR: Backup file not found: ${BACKUP_FILE}"
  exit 1
fi

echo "[restore-users] Starting restore"
echo "[restore-users] DATABASE_URL=${DATABASE_URL}"
echo "[restore-users] BACKUP_FILE=${BACKUP_FILE}"

pg_restore \
  --clean \
  --if-exists \
  --no-owner \
  --dbname="${DATABASE_URL}" \
  "${BACKUP_FILE}"

if [[ "${REVOKE_SESSIONS_AFTER_RESTORE}" == "true" ]]; then
  echo "[restore-users] Revoking all restored sessions"
  psql "${DATABASE_URL}" -v ON_ERROR_STOP=1 -c "UPDATE user_session SET revoked = TRUE;"
fi

echo "[restore-users] Restore completed successfully"
