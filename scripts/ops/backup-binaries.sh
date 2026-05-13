#!/usr/bin/env bash
# Empaqueta los directorios binarios del nodo (client-files + staging + recovery)
# en un tar.gz. Scope independiente del backup de Postgres.
#
# Uso:
#   ./scripts/ops/backup-binaries.sh                                # default paths
#   BACKUP_DIR=/tmp/bk ./scripts/ops/backup-binaries.sh
#   INCLUDE_RECOVERY_PAYLOAD=true ./scripts/ops/backup-binaries.sh
#   ./scripts/ops/backup-binaries.sh --help
#
# Salidas:
#   exit 0       backup creado correctamente
#   exit !=0     error de IO o backup vacío con ALLOW_EMPTY_BINARY_BACKUP=false
#   artifact     ${BACKUP_DIR}/node-binaries-<timestamp>.tar.gz

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_BACKUP_DIR="${SCRIPT_DIR}/../../backups"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

BACKUP_DIR="${BACKUP_DIR:-${DEFAULT_BACKUP_DIR}}"
BACKUP_FILE="${BACKUP_FILE:-${BACKUP_DIR}/node-binaries-${TIMESTAMP}.tar.gz}"

CLIENT_FILES_BASE_DIRECTORY="${CLIENT_FILES_BASE_DIRECTORY:-./data/client-files}"
CLIENT_FILES_STAGING_DIRECTORY="${CLIENT_FILES_STAGING_DIRECTORY:-./data/client-files-staging}"
RECOVERY_PAYLOAD_DIRECTORY="${RECOVERY_PAYLOAD_DIRECTORY:-./logs/recovery-payload}"

INCLUDE_STAGING="${INCLUDE_STAGING:-true}"
INCLUDE_RECOVERY_PAYLOAD="${INCLUDE_RECOVERY_PAYLOAD:-false}"
ALLOW_EMPTY_BINARY_BACKUP="${ALLOW_EMPTY_BINARY_BACKUP:-false}"

if [[ "${1:-}" == "--help" ]]; then
  cat <<EOF
Usage: BACKUP_FILE=/path/to/archive.tar.gz scripts/ops/backup-binaries.sh

Creates a binary-scope backup archive for client content directories.

Environment variables:
  BACKUP_DIR                         Target directory for backup artifact
  BACKUP_FILE                        Target archive path (default: backups/node-binaries-<timestamp>.tar.gz)
  CLIENT_FILES_BASE_DIRECTORY        Client content directory (default: ./data/client-files)
  CLIENT_FILES_STAGING_DIRECTORY     Upload staging directory (default: ./data/client-files-staging)
  RECOVERY_PAYLOAD_DIRECTORY         Optional recovery payload directory (default: ./logs/recovery-payload)
  INCLUDE_STAGING                    true|false (default: true)
  INCLUDE_RECOVERY_PAYLOAD           true|false (default: false)
  ALLOW_EMPTY_BINARY_BACKUP          true|false (default: false)
EOF
  exit 0
fi

if [[ $# -gt 0 ]]; then
  echo "[backup-binaries] ERROR unsupported argument: $1"
  echo "[backup-binaries] Use --help for usage"
  exit 1
fi

TMP_DIR="$(/usr/bin/mktemp -d)"
cleanup() {
  /bin/rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

STAGE_DIR="${TMP_DIR}/binary-scope"
MANIFEST_FILE="${STAGE_DIR}/manifest.txt"
COPIED_DIRECTORIES=0

/bin/mkdir -p "${BACKUP_DIR}"
/bin/mkdir -p "${STAGE_DIR}"

echo "scope=binaries" > "${MANIFEST_FILE}"
echo "generatedAt=${TIMESTAMP}" >> "${MANIFEST_FILE}"

copy_dir_if_exists() {
  local source_dir="$1"
  local target_name="$2"

  if [[ -d "${source_dir}" ]]; then
    /bin/mkdir -p "${STAGE_DIR}/${target_name}"
    /bin/cp -R "${source_dir}/." "${STAGE_DIR}/${target_name}/"
    echo "${target_name}=${source_dir}" >> "${MANIFEST_FILE}"
    COPIED_DIRECTORIES=$((COPIED_DIRECTORIES + 1))
    echo "[backup-binaries] included ${source_dir} -> ${target_name}"
  else
    echo "[backup-binaries] WARN missing directory, skipped: ${source_dir}"
  fi
}

echo "[backup-binaries] Starting binary scope backup"
echo "[backup-binaries] BACKUP_FILE=${BACKUP_FILE}"

copy_dir_if_exists "${CLIENT_FILES_BASE_DIRECTORY}" "client-files"

if [[ "${INCLUDE_STAGING}" == "true" ]]; then
  copy_dir_if_exists "${CLIENT_FILES_STAGING_DIRECTORY}" "client-files-staging"
fi

if [[ "${INCLUDE_RECOVERY_PAYLOAD}" == "true" ]]; then
  copy_dir_if_exists "${RECOVERY_PAYLOAD_DIRECTORY}" "recovery-payload"
fi

if [[ ${COPIED_DIRECTORIES} -eq 0 && "${ALLOW_EMPTY_BINARY_BACKUP}" != "true" ]]; then
  echo "[backup-binaries] ERROR no binary directory could be included"
  echo "[backup-binaries] HINT set ALLOW_EMPTY_BINARY_BACKUP=true to force empty archive"
  exit 1
fi

/usr/bin/tar -czf "${BACKUP_FILE}" -C "${STAGE_DIR}" .

echo "[backup-binaries] Backup created successfully: ${BACKUP_FILE}"
echo "[backup-binaries] Included directories: ${COPIED_DIRECTORIES}"
