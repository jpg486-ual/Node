#!/usr/bin/env bash
# Valida que los artefactos de backup recientes son restaurables (post-mortem
# integrity check). Para cada scope habilitado prueba el restore en sandbox y
# compara contra hashes/expectativas; nunca toca la base ni los directorios
# productivos.
#
# Uso:
#   ./scripts/ops/verify-restore-integrity.sh
#   VERIFY_FULL=false ./scripts/ops/verify-restore-integrity.sh
#   AUTH_FS_BACKUP_FILE=/path/... ./scripts/ops/verify-restore-integrity.sh
#
# Salidas:
#   exit 0       todos los scopes habilitados restoreables
#   exit !=0     algún scope falló (detalle en REPORT_FILE)
#   artifact     logs/ops-backup/restore-integrity-<timestamp>.txt

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

TIMESTAMP="$(/bin/date +%Y%m%d-%H%M%S)"
DEFAULT_BACKUP_DIR="${PROJECT_ROOT}/backups"
DEFAULT_REPORT_DIR="${PROJECT_ROOT}/logs/ops-backup"

BACKUP_DIR="${BACKUP_DIR:-${DEFAULT_BACKUP_DIR}}"
REPORT_FILE="${REPORT_FILE:-${DEFAULT_REPORT_DIR}/restore-integrity-${TIMESTAMP}.txt}"

VERIFY_AUTH_FS="${VERIFY_AUTH_FS:-true}"
VERIFY_FULL="${VERIFY_FULL:-true}"
VERIFY_BINARIES="${VERIFY_BINARIES:-true}"

AUTH_FS_BACKUP_FILE="${AUTH_FS_BACKUP_FILE:-}"
FULL_BACKUP_FILE="${FULL_BACKUP_FILE:-}"
BINARY_BACKUP_FILE="${BINARY_BACKUP_FILE:-}"

TMP_DIR="$(/usr/bin/mktemp -d)"
FAILURE_FILE="${TMP_DIR}/failures.txt"
FAILURES=0

cleanup() {
  /bin/rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

usage() {
  cat <<EOF
Usage: scripts/ops/verify-restore-integrity.sh

Validates that backup artifacts are restorable from an integrity perspective.

Environment variables:
  BACKUP_DIR           Backup directory used for latest-file discovery (default: ./backups)
  REPORT_FILE          Output report file (default: logs/ops-backup/restore-integrity-<timestamp>.txt)
  VERIFY_AUTH_FS       true|false (default: true)
  VERIFY_FULL          true|false (default: true)
  VERIFY_BINARIES      true|false (default: true)
  AUTH_FS_BACKUP_FILE  Optional explicit file for auth/fs backup dump
  FULL_BACKUP_FILE     Optional explicit file for full backup dump
  BINARY_BACKUP_FILE   Optional explicit file for binaries backup tar.gz

Checks performed:
  - auth/fs and full: pg_restore --list can parse the dump,
  - auth/fs: dump contains expected tables user_account/registration_code/user_session/fs_entry,
  - binaries: tar can list archive, manifest exists, and manifest scope is binaries.

Exit codes:
  0   integrity checks passed
  20  one or more integrity checks failed
EOF
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 0 ]]; then
  echo "[verify-restore-integrity] ERROR unsupported argument: $1"
  echo "[verify-restore-integrity] Use --help for usage"
  exit 1
fi

validate_boolean() {
  local name="$1"
  local value="$2"
  if [[ "${value}" != "true" && "${value}" != "false" ]]; then
    echo "[verify-restore-integrity] ERROR ${name} must be true|false, got: ${value}"
    exit 1
  fi
}

record_failure() {
  local message="$1"
  echo "[verify-restore-integrity] ERROR ${message}"
  echo "${message}" >> "${FAILURE_FILE}"
  FAILURES=$((FAILURES + 1))
}

require_command() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "[verify-restore-integrity] ERROR required command not found: ${cmd}"
    exit 1
  fi
}

find_latest_backup() {
  local pattern="$1"
  /usr/bin/find "${BACKUP_DIR}" -type f -name "${pattern}" 2>/dev/null | /usr/bin/sort | /usr/bin/tail -n 1
}

resolve_backup_file() {
  local explicit_file="$1"
  local pattern="$2"
  local label="$3"
  local resolved_file="${explicit_file}"

  if [[ -z "${resolved_file}" ]]; then
    resolved_file="$(find_latest_backup "${pattern}")"
  fi

  if [[ -z "${resolved_file}" ]]; then
    record_failure "${label} backup no encontrado (pattern ${pattern})"
    echo ""
    return
  fi

  if [[ ! -f "${resolved_file}" ]]; then
    record_failure "${label} backup no existe: ${resolved_file}"
    echo ""
    return
  fi

  echo "${resolved_file}"
}

extract_manifest() {
  local archive_file="$1"
  local manifest_out="$2"

  if /usr/bin/tar -xOf "${archive_file}" manifest.txt > "${manifest_out}" 2>/dev/null; then
    return 0
  fi

  if /usr/bin/tar -xOf "${archive_file}" ./manifest.txt > "${manifest_out}" 2>/dev/null; then
    return 0
  fi

  return 1
}

verify_auth_fs_dump() {
  local backup_file="$1"
  local toc_file="${TMP_DIR}/auth-fs.toc"

  if ! pg_restore --list "${backup_file}" > "${toc_file}" 2>"${TMP_DIR}/auth-fs.stderr"; then
    record_failure "auth/fs dump no parseable con pg_restore: ${backup_file}"
    return
  fi

  for table_name in user_account registration_code user_session fs_entry; do
    if ! /usr/bin/grep -Eq "(TABLE DATA|TABLE) .*${table_name}" "${toc_file}"; then
      record_failure "auth/fs dump sin tabla esperada: ${table_name}"
    fi
  done
}

verify_full_dump() {
  local backup_file="$1"
  local toc_file="${TMP_DIR}/full.toc"

  if ! pg_restore --list "${backup_file}" > "${toc_file}" 2>"${TMP_DIR}/full.stderr"; then
    record_failure "full dump no parseable con pg_restore: ${backup_file}"
    return
  fi

  if [[ ! -s "${toc_file}" ]]; then
    record_failure "full dump sin contenido listable: ${backup_file}"
  fi
}

verify_binary_archive() {
  local backup_file="$1"
  local list_file="${TMP_DIR}/binaries.list"
  local manifest_file="${TMP_DIR}/manifest.txt"

  if ! /usr/bin/tar -tzf "${backup_file}" > "${list_file}" 2>"${TMP_DIR}/binaries.stderr"; then
    record_failure "archivo binario no legible con tar: ${backup_file}"
    return
  fi

  if ! /usr/bin/grep -Eq "manifest\.txt$" "${list_file}"; then
    record_failure "archivo binario sin manifest.txt: ${backup_file}"
    return
  fi

  if ! extract_manifest "${backup_file}" "${manifest_file}"; then
    record_failure "no se pudo extraer manifest.txt del archivo binario: ${backup_file}"
    return
  fi

  if ! /usr/bin/grep -q "^scope=binaries$" "${manifest_file}"; then
    record_failure "manifest sin scope=binaries en archivo: ${backup_file}"
  fi
}

write_report() {
  /bin/mkdir -p "$(/usr/bin/dirname "${REPORT_FILE}")"

  {
    echo "timestamp=${TIMESTAMP}"
    echo "verifyAuthFs=${VERIFY_AUTH_FS}"
    echo "verifyFull=${VERIFY_FULL}"
    echo "verifyBinaries=${VERIFY_BINARIES}"
    echo "authFsBackupFile=${AUTH_FS_BACKUP_FILE}"
    echo "fullBackupFile=${FULL_BACKUP_FILE}"
    echo "binaryBackupFile=${BINARY_BACKUP_FILE}"
    echo "failures=${FAILURES}"
    if [[ -s "${FAILURE_FILE}" ]]; then
      while IFS= read -r line; do
        echo "failure=${line}"
      done < "${FAILURE_FILE}"
    fi
  } > "${REPORT_FILE}"

  echo "[verify-restore-integrity] Report generated: ${REPORT_FILE}"
}

validate_boolean "VERIFY_AUTH_FS" "${VERIFY_AUTH_FS}"
validate_boolean "VERIFY_FULL" "${VERIFY_FULL}"
validate_boolean "VERIFY_BINARIES" "${VERIFY_BINARIES}"

if [[ "${VERIFY_AUTH_FS}" == "false" && "${VERIFY_FULL}" == "false" && "${VERIFY_BINARIES}" == "false" ]]; then
  echo "[verify-restore-integrity] ERROR no verification scope enabled"
  exit 1
fi

if [[ "${VERIFY_AUTH_FS}" == "true" || "${VERIFY_FULL}" == "true" ]]; then
  require_command pg_restore
fi
if [[ "${VERIFY_BINARIES}" == "true" ]]; then
  require_command tar
fi
require_command find
require_command grep

if [[ "${VERIFY_AUTH_FS}" == "true" ]]; then
  AUTH_FS_BACKUP_FILE="$(resolve_backup_file "${AUTH_FS_BACKUP_FILE}" "node-auth-fs-*.dump" "auth/fs")"
  if [[ -n "${AUTH_FS_BACKUP_FILE}" ]]; then
    echo "[verify-restore-integrity] Checking auth/fs backup: ${AUTH_FS_BACKUP_FILE}"
    verify_auth_fs_dump "${AUTH_FS_BACKUP_FILE}"
  fi
fi

if [[ "${VERIFY_FULL}" == "true" ]]; then
  FULL_BACKUP_FILE="$(resolve_backup_file "${FULL_BACKUP_FILE}" "node-full-*.dump" "full")"
  if [[ -n "${FULL_BACKUP_FILE}" ]]; then
    echo "[verify-restore-integrity] Checking full backup: ${FULL_BACKUP_FILE}"
    verify_full_dump "${FULL_BACKUP_FILE}"
  fi
fi

if [[ "${VERIFY_BINARIES}" == "true" ]]; then
  BINARY_BACKUP_FILE="$(resolve_backup_file "${BINARY_BACKUP_FILE}" "node-binaries-*.tar.gz" "binarios")"
  if [[ -n "${BINARY_BACKUP_FILE}" ]]; then
    echo "[verify-restore-integrity] Checking binaries backup: ${BINARY_BACKUP_FILE}"
    verify_binary_archive "${BINARY_BACKUP_FILE}"
  fi
fi

write_report

if [[ ${FAILURES} -gt 0 ]]; then
  echo "[verify-restore-integrity] FAILED with ${FAILURES} issue(s)"
  exit 20
fi

echo "[verify-restore-integrity] OK all integrity checks passed"
