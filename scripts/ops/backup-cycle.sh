#!/usr/bin/env bash
# Orquesta un ciclo completo de backup: Postgres (auth-fs y/o full) + binarios +
# verificación de restore-integrity. Aplica retención por días y emite un report
# del ciclo.
#
# Uso:
#   ./scripts/ops/backup-cycle.sh                          # auth-fs + binarios + integrity
#   RUN_FULL_BACKUP=true ./scripts/ops/backup-cycle.sh     # añade dump full
#   RETENTION_DAYS=14 ./scripts/ops/backup-cycle.sh
#
# Salidas:
#   exit 0       todo el ciclo OK
#   exit !=0     algún paso falló (ver report)
#   artifacts    backups/*.dump|*.tar.gz + logs/ops-backup/backup-cycle-<ts>.txt

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

TIMESTAMP="$(/bin/date +%Y%m%d-%H%M%S)"
DEFAULT_BACKUP_DIR="${PROJECT_ROOT}/backups"
DEFAULT_REPORT_DIR="${PROJECT_ROOT}/logs/ops-backup"

DATABASE_URL="${DATABASE_URL:-postgresql://node:node@localhost:5432/node}"
BACKUP_DIR="${BACKUP_DIR:-${DEFAULT_BACKUP_DIR}}"
REPORT_DIR="${REPORT_DIR:-${DEFAULT_REPORT_DIR}}"
CYCLE_REPORT_FILE="${CYCLE_REPORT_FILE:-${REPORT_DIR}/backup-cycle-${TIMESTAMP}.txt}"
INTEGRITY_REPORT_FILE="${INTEGRITY_REPORT_FILE:-${REPORT_DIR}/restore-integrity-${TIMESTAMP}.txt}"

RUN_AUTH_FS_BACKUP="${RUN_AUTH_FS_BACKUP:-true}"
RUN_FULL_BACKUP="${RUN_FULL_BACKUP:-false}"
RUN_BINARIES_BACKUP="${RUN_BINARIES_BACKUP:-true}"
RUN_RESTORE_INTEGRITY_CHECK="${RUN_RESTORE_INTEGRITY_CHECK:-true}"

RETENTION_DAYS="${RETENTION_DAYS:-7}"

INCLUDE_STAGING="${INCLUDE_STAGING:-true}"
INCLUDE_RECOVERY_PAYLOAD="${INCLUDE_RECOVERY_PAYLOAD:-false}"
ALLOW_EMPTY_BINARY_BACKUP="${ALLOW_EMPTY_BINARY_BACKUP:-false}"

BACKUP_ERRORS=0
REMOVED_OLD_BACKUPS=0
RESTORE_CHECK_STATUS="not-run"

AUTH_FS_BACKUP_FILE=""
FULL_BACKUP_FILE=""
BINARY_BACKUP_FILE=""

usage() {
  cat <<EOF
Usage: scripts/ops/backup-cycle.sh

Runs a repeatable backup cycle by scope and optional restore integrity validation.

Environment variables:
  DATABASE_URL                 PostgreSQL URL used by backup-users.sh
  BACKUP_DIR                   Output directory for artifacts (default: ./backups)
  REPORT_DIR                   Output directory for cycle/integrity reports
  CYCLE_REPORT_FILE            Explicit cycle report file path
  INTEGRITY_REPORT_FILE        Explicit integrity report file path
  RUN_AUTH_FS_BACKUP           true|false (default: true)
  RUN_FULL_BACKUP              true|false (default: false)
  RUN_BINARIES_BACKUP          true|false (default: true)
  RUN_RESTORE_INTEGRITY_CHECK  true|false (default: true)
  RETENTION_DAYS               >=0 remove old artifacts older than N days (0 disables cleanup)

Forwarded to binary scope backup:
  INCLUDE_STAGING
  INCLUDE_RECOVERY_PAYLOAD
  ALLOW_EMPTY_BINARY_BACKUP

Exit codes:
  0   backup cycle completed successfully
  20  one or more backup/verification steps failed
EOF
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 0 ]]; then
  echo "[backup-cycle] ERROR unsupported argument: $1"
  echo "[backup-cycle] Use --help for usage"
  exit 1
fi

validate_boolean() {
  local name="$1"
  local value="$2"
  if [[ "${value}" != "true" && "${value}" != "false" ]]; then
    echo "[backup-cycle] ERROR ${name} must be true|false, got: ${value}"
    exit 1
  fi
}

run_with_status() {
  local label="$1"
  shift

  echo "[backup-cycle] Running ${label}"
  if "$@"; then
    echo "[backup-cycle] OK ${label}"
  else
    BACKUP_ERRORS=$((BACKUP_ERRORS + 1))
    echo "[backup-cycle] ERROR ${label}"
  fi
}

run_auth_fs_backup() {
  AUTH_FS_BACKUP_FILE="${BACKUP_DIR}/node-auth-fs-${TIMESTAMP}.dump"
  DATABASE_URL="${DATABASE_URL}" \
    BACKUP_SCOPE="auth-fs" \
    BACKUP_FILE="${AUTH_FS_BACKUP_FILE}" \
    "${SCRIPT_DIR}/backup-users.sh"
}

run_full_backup() {
  FULL_BACKUP_FILE="${BACKUP_DIR}/node-full-${TIMESTAMP}.dump"
  DATABASE_URL="${DATABASE_URL}" \
    BACKUP_SCOPE="full" \
    BACKUP_FILE="${FULL_BACKUP_FILE}" \
    "${SCRIPT_DIR}/backup-users.sh"
}

run_binaries_backup() {
  BINARY_BACKUP_FILE="${BACKUP_DIR}/node-binaries-${TIMESTAMP}.tar.gz"
  BACKUP_FILE="${BINARY_BACKUP_FILE}" \
    INCLUDE_STAGING="${INCLUDE_STAGING}" \
    INCLUDE_RECOVERY_PAYLOAD="${INCLUDE_RECOVERY_PAYLOAD}" \
    ALLOW_EMPTY_BINARY_BACKUP="${ALLOW_EMPTY_BINARY_BACKUP}" \
    "${SCRIPT_DIR}/backup-binaries.sh"
}

run_restore_integrity_check() {
  if VERIFY_AUTH_FS="${RUN_AUTH_FS_BACKUP}" \
    VERIFY_FULL="${RUN_FULL_BACKUP}" \
    VERIFY_BINARIES="${RUN_BINARIES_BACKUP}" \
    AUTH_FS_BACKUP_FILE="${AUTH_FS_BACKUP_FILE}" \
    FULL_BACKUP_FILE="${FULL_BACKUP_FILE}" \
    BINARY_BACKUP_FILE="${BINARY_BACKUP_FILE}" \
    BACKUP_DIR="${BACKUP_DIR}" \
    REPORT_FILE="${INTEGRITY_REPORT_FILE}" \
    "${SCRIPT_DIR}/verify-restore-integrity.sh"; then
    RESTORE_CHECK_STATUS="ok"
    return 0
  fi

  RESTORE_CHECK_STATUS="failed"
  return 1
}

apply_retention_cleanup() {
  if [[ "${RETENTION_DAYS}" -eq 0 ]]; then
    echo "[backup-cycle] Retention disabled (RETENTION_DAYS=0)"
    return
  fi

  while IFS= read -r old_backup; do
    if [[ -z "${old_backup}" ]]; then
      continue
    fi

    /bin/rm -f "${old_backup}"
    REMOVED_OLD_BACKUPS=$((REMOVED_OLD_BACKUPS + 1))
    echo "[backup-cycle] Removed old backup: ${old_backup}"
  done < <(
    /usr/bin/find "${BACKUP_DIR}" -type f \( \
      -name "node-auth-fs-*.dump" -o \
      -name "node-full-*.dump" -o \
      -name "node-binaries-*.tar.gz" \
    \) -mtime +"${RETENTION_DAYS}" 2>/dev/null
  )

  echo "[backup-cycle] Old backups removed: ${REMOVED_OLD_BACKUPS}"
}

write_cycle_report() {
  {
    echo "timestamp=${TIMESTAMP}"
    echo "runAuthFsBackup=${RUN_AUTH_FS_BACKUP}"
    echo "runFullBackup=${RUN_FULL_BACKUP}"
    echo "runBinariesBackup=${RUN_BINARIES_BACKUP}"
    echo "runRestoreIntegrityCheck=${RUN_RESTORE_INTEGRITY_CHECK}"
    echo "authFsBackupFile=${AUTH_FS_BACKUP_FILE}"
    echo "fullBackupFile=${FULL_BACKUP_FILE}"
    echo "binaryBackupFile=${BINARY_BACKUP_FILE}"
    echo "restoreCheckStatus=${RESTORE_CHECK_STATUS}"
    echo "backupErrors=${BACKUP_ERRORS}"
    echo "retentionDays=${RETENTION_DAYS}"
    echo "removedOldBackups=${REMOVED_OLD_BACKUPS}"
    echo "integrityReportFile=${INTEGRITY_REPORT_FILE}"
  } > "${CYCLE_REPORT_FILE}"

  echo "[backup-cycle] Report generated: ${CYCLE_REPORT_FILE}"
}

validate_boolean "RUN_AUTH_FS_BACKUP" "${RUN_AUTH_FS_BACKUP}"
validate_boolean "RUN_FULL_BACKUP" "${RUN_FULL_BACKUP}"
validate_boolean "RUN_BINARIES_BACKUP" "${RUN_BINARIES_BACKUP}"
validate_boolean "RUN_RESTORE_INTEGRITY_CHECK" "${RUN_RESTORE_INTEGRITY_CHECK}"
validate_boolean "INCLUDE_STAGING" "${INCLUDE_STAGING}"
validate_boolean "INCLUDE_RECOVERY_PAYLOAD" "${INCLUDE_RECOVERY_PAYLOAD}"
validate_boolean "ALLOW_EMPTY_BINARY_BACKUP" "${ALLOW_EMPTY_BINARY_BACKUP}"

if [[ ! "${RETENTION_DAYS}" =~ ^[0-9]+$ ]]; then
  echo "[backup-cycle] ERROR RETENTION_DAYS must be a non-negative integer"
  exit 1
fi

if [[ "${RUN_AUTH_FS_BACKUP}" == "false" && "${RUN_FULL_BACKUP}" == "false" && "${RUN_BINARIES_BACKUP}" == "false" ]]; then
  echo "[backup-cycle] ERROR no backup scope enabled"
  exit 1
fi

/bin/mkdir -p "${BACKUP_DIR}"
/bin/mkdir -p "${REPORT_DIR}"

if [[ "${RUN_RESTORE_INTEGRITY_CHECK}" == "false" ]]; then
  RESTORE_CHECK_STATUS="disabled"
fi

if [[ "${RUN_AUTH_FS_BACKUP}" == "true" ]]; then
  run_with_status "auth/fs backup" run_auth_fs_backup
fi

if [[ "${RUN_FULL_BACKUP}" == "true" ]]; then
  run_with_status "full backup" run_full_backup
fi

if [[ "${RUN_BINARIES_BACKUP}" == "true" ]]; then
  run_with_status "binaries backup" run_binaries_backup
fi

apply_retention_cleanup

if [[ "${RUN_RESTORE_INTEGRITY_CHECK}" == "true" ]]; then
  if [[ ${BACKUP_ERRORS} -gt 0 ]]; then
    RESTORE_CHECK_STATUS="skipped-due-to-backup-errors"
    echo "[backup-cycle] Skipping restore integrity check because backup errors were detected"
  else
    run_with_status "restore integrity check" run_restore_integrity_check
  fi
fi

write_cycle_report

if [[ ${BACKUP_ERRORS} -gt 0 ]]; then
  echo "[backup-cycle] FAILED with ${BACKUP_ERRORS} issue(s)"
  exit 20
fi

echo "[backup-cycle] Cycle completed successfully"
