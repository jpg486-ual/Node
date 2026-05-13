#!/usr/bin/env bash
# Restaura un archivo tar.gz de binarios (creado por backup-binaries.sh) en los
# directorios locales del nodo (client-files, staging, recovery-payload).
#
# Uso:
#   BACKUP_FILE=/path/to/node-binaries-*.tar.gz ./scripts/ops/restore-binaries.sh
#   PURGE_TARGET_DIRECTORIES=true BACKUP_FILE=... ./scripts/ops/restore-binaries.sh
#
# Salidas:
#   exit 0       restore completado
#   exit !=0    BACKUP_FILE ausente/inexistente o tar falló
#
# Atención: PURGE_TARGET_DIRECTORIES=true vacía los destinos antes de extraer.

set -euo pipefail

BACKUP_FILE="${BACKUP_FILE:-}"

CLIENT_FILES_BASE_DIRECTORY="${CLIENT_FILES_BASE_DIRECTORY:-./data/client-files}"
CLIENT_FILES_STAGING_DIRECTORY="${CLIENT_FILES_STAGING_DIRECTORY:-./data/client-files-staging}"
RECOVERY_PAYLOAD_DIRECTORY="${RECOVERY_PAYLOAD_DIRECTORY:-./logs/recovery-payload}"

RESTORE_CLIENT_FILES="${RESTORE_CLIENT_FILES:-true}"
RESTORE_STAGING="${RESTORE_STAGING:-true}"
RESTORE_RECOVERY_PAYLOAD="${RESTORE_RECOVERY_PAYLOAD:-true}"
PURGE_TARGET_DIRECTORIES="${PURGE_TARGET_DIRECTORIES:-false}"

if [[ "${1:-}" == "--help" ]]; then
  cat <<EOF
Usage: BACKUP_FILE=/path/to/node-binaries-*.tar.gz scripts/ops/restore-binaries.sh

Restores a binary-scope backup archive into configured local directories.

Environment variables:
  BACKUP_FILE                        Required backup archive path
  CLIENT_FILES_BASE_DIRECTORY        Client content directory (default: ./data/client-files)
  CLIENT_FILES_STAGING_DIRECTORY     Upload staging directory (default: ./data/client-files-staging)
  RECOVERY_PAYLOAD_DIRECTORY         Recovery payload directory (default: ./logs/recovery-payload)
  RESTORE_CLIENT_FILES               true|false (default: true)
  RESTORE_STAGING                    true|false (default: true)
  RESTORE_RECOVERY_PAYLOAD           true|false (default: true)
  PURGE_TARGET_DIRECTORIES           true|false (default: false)
EOF
  exit 0
fi

if [[ $# -gt 0 ]]; then
  echo "[restore-binaries] ERROR unsupported argument: $1"
  echo "[restore-binaries] Use --help for usage"
  exit 1
fi

if [[ -z "${BACKUP_FILE}" ]]; then
  echo "[restore-binaries] ERROR: BACKUP_FILE is required"
  echo "Usage: BACKUP_FILE=/path/to/node-binaries-*.tar.gz scripts/ops/restore-binaries.sh"
  exit 1
fi

if [[ ! -f "${BACKUP_FILE}" ]]; then
  echo "[restore-binaries] ERROR: Backup file not found: ${BACKUP_FILE}"
  exit 1
fi

TMP_DIR="$(/usr/bin/mktemp -d)"
cleanup() {
  /bin/rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

echo "[restore-binaries] Starting binary scope restore"
echo "[restore-binaries] BACKUP_FILE=${BACKUP_FILE}"

/usr/bin/tar -xzf "${BACKUP_FILE}" -C "${TMP_DIR}"

if [[ -f "${TMP_DIR}/manifest.txt" ]]; then
  echo "[restore-binaries] Manifest found"
  /bin/cat "${TMP_DIR}/manifest.txt"
else
  echo "[restore-binaries] WARN manifest.txt not found in archive"
fi

RESTORED_DIRECTORIES=0

restore_one_directory() {
  local enabled="$1"
  local stage_name="$2"
  local target_dir="$3"

  if [[ "${enabled}" != "true" ]]; then
    return
  fi

  local stage_path="${TMP_DIR}/${stage_name}"
  if [[ ! -d "${stage_path}" ]]; then
    echo "[restore-binaries] WARN missing archive path, skipped: ${stage_name}"
    return
  fi

  if [[ "${PURGE_TARGET_DIRECTORIES}" == "true" ]]; then
    /bin/rm -rf "${target_dir}"
  fi

  /bin/mkdir -p "${target_dir}"
  /bin/cp -R "${stage_path}/." "${target_dir}/"
  RESTORED_DIRECTORIES=$((RESTORED_DIRECTORIES + 1))
  echo "[restore-binaries] restored ${stage_name} -> ${target_dir}"
}

restore_one_directory "${RESTORE_CLIENT_FILES}" "client-files" "${CLIENT_FILES_BASE_DIRECTORY}"
restore_one_directory "${RESTORE_STAGING}" "client-files-staging" "${CLIENT_FILES_STAGING_DIRECTORY}"
restore_one_directory "${RESTORE_RECOVERY_PAYLOAD}" "recovery-payload" "${RECOVERY_PAYLOAD_DIRECTORY}"

if [[ ${RESTORED_DIRECTORIES} -eq 0 ]]; then
  echo "[restore-binaries] ERROR no directory could be restored"
  exit 1
fi

echo "[restore-binaries] Restore completed successfully"
echo "[restore-binaries] Restored directories: ${RESTORED_DIRECTORIES}"
