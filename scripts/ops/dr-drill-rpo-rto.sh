#!/usr/bin/env bash
# Ejecuta un DR drill que mide RPO y RTO observados contra los objetivos
# configurados (default RPO=6h, RTO=15min) e invoca el smoke recovery
# end-to-end como prueba real del flow de reconstrucción.
#
# Uso:
#   ./scripts/ops/dr-drill-rpo-rto.sh
#   ./scripts/ops/dr-drill-rpo-rto.sh --rpo-target-seconds 3600
#   ./scripts/ops/dr-drill-rpo-rto.sh --cycle-report logs/ops-backup/...txt
#
# Salidas:
#   exit 0       RPO + RTO observados dentro de targets
#   exit !=0     algún SLO incumplido o smoke recovery falló
#   artifacts    logs/archive/dr-rpo-rto-<timestamp>.txt + smoke log

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

TIMESTAMP="$(/bin/date +%Y%m%d-%H%M%S)"

DEFAULT_REPORT_DIR="${PROJECT_ROOT}/logs/archive"
DR_CYCLE_REPORT_FILE="${DR_CYCLE_REPORT_FILE:-}"
DR_REPORT_FILE="${DR_REPORT_FILE:-${DEFAULT_REPORT_DIR}/dr-rpo-rto-${TIMESTAMP}.txt}"
DR_SMOKE_LOG_FILE="${DR_SMOKE_LOG_FILE:-${DEFAULT_REPORT_DIR}/dr-rpo-rto-smoke-${TIMESTAMP}.log}"
DR_SMOKE_SCRIPT="${DR_SMOKE_SCRIPT:-${PROJECT_ROOT}/docker/scripts/smoke-recovery-full-flow.sh}"
DR_SMOKE_ARGS="${DR_SMOKE_ARGS:---no-build}"

RPO_TARGET_SECONDS="${RPO_TARGET_SECONDS:-21600}"
RTO_TARGET_SECONDS="${RTO_TARGET_SECONDS:-900}"

usage() {
  cat <<EOF
Usage: scripts/ops/dr-drill-rpo-rto.sh [options]

Executes a DR drill and produces an observed RPO/RTO report.

Options:
  --cycle-report <file>        Backup cycle report file to use as RPO reference
  --output <file>              DR report output file
  --smoke-log <file>           Smoke execution log file
  --smoke-args "<args>"        Args passed to smoke-recovery-full-flow.sh
  --rpo-target-seconds <n>     RPO objective in seconds (default: 21600)
  --rto-target-seconds <n>     RTO objective in seconds (default: 900)
  --help                       Show this help

Environment variables:
  DR_CYCLE_REPORT_FILE
  DR_REPORT_FILE
  DR_SMOKE_LOG_FILE
  DR_SMOKE_SCRIPT
  DR_SMOKE_ARGS
  RPO_TARGET_SECONDS
  RTO_TARGET_SECONDS

Exit codes:
  0   drill executed and RPO/RTO objectives met
  10  drill executed but one or more objectives were missed
  20  drill failed (preconditions or smoke execution)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cycle-report)
      DR_CYCLE_REPORT_FILE="${2:-}"
      shift
      ;;
    --output)
      DR_REPORT_FILE="${2:-}"
      shift
      ;;
    --smoke-log)
      DR_SMOKE_LOG_FILE="${2:-}"
      shift
      ;;
    --smoke-args)
      DR_SMOKE_ARGS="${2:-}"
      shift
      ;;
    --rpo-target-seconds)
      RPO_TARGET_SECONDS="${2:-}"
      shift
      ;;
    --rto-target-seconds)
      RTO_TARGET_SECONDS="${2:-}"
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "[dr-drill] ERROR unknown option: $1"
      usage
      exit 1
      ;;
  esac
  shift
done

require_command() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "[dr-drill] ERROR required command not found: ${cmd}"
    exit 20
  fi
}

find_latest_cycle_report() {
  # `find` retorna no-zero si alguno de los paths no existe, lo que
  # rompe `set -e -o pipefail` aunque haya match en el otro path. La
  # primera ejecución limpia del repo no tiene logs/ops-backup creado
  # todavía, así que `mkdir -p` ambos paths antes de invocar find.
  /bin/mkdir -p "${PROJECT_ROOT}/logs/ops-backup" "${PROJECT_ROOT}/logs/archive"
  /usr/bin/find "${PROJECT_ROOT}/logs/ops-backup" "${PROJECT_ROOT}/logs/archive" -type f -name "backup-cycle-*.txt" 2>/dev/null \
    | /usr/bin/sort \
    | /usr/bin/tail -n 1
}

read_report_value() {
  local file_path="$1"
  local key="$2"

  /usr/bin/awk -F= -v expected_key="${key}" '$1 == expected_key {print substr($0, index($0, "=") + 1)}' "${file_path}" \
    | /usr/bin/tail -n 1
}

timestamp_to_epoch() {
  local ts="$1"

  if /bin/date -j -f "%Y%m%d-%H%M%S" "${ts}" "+%s" >/dev/null 2>&1; then
    /bin/date -j -f "%Y%m%d-%H%M%S" "${ts}" "+%s"
    return 0
  fi

  if /bin/date -d "${ts:0:8} ${ts:9:2}:${ts:11:2}:${ts:13:2}" "+%s" >/dev/null 2>&1; then
    /bin/date -d "${ts:0:8} ${ts:9:2}:${ts:11:2}:${ts:13:2}" "+%s"
    return 0
  fi

  echo "[dr-drill] ERROR unsupported date conversion for timestamp: ${ts}"
  exit 20
}

write_report() {
  local objective_rpo_met="$1"
  local objective_rto_met="$2"
  local smoke_exit_code="$3"
  local cycle_timestamp="$4"
  local rpo_observed_seconds="$5"
  local rto_observed_seconds="$6"

  /bin/mkdir -p "$(/usr/bin/dirname "${DR_REPORT_FILE}")"
  {
    echo "generatedAt=${TIMESTAMP}"
    echo "cycleReportFile=${DR_CYCLE_REPORT_FILE}"
    echo "cycleTimestamp=${cycle_timestamp}"
    echo "smokeScript=${DR_SMOKE_SCRIPT}"
    echo "smokeArgs=${DR_SMOKE_ARGS}"
    echo "smokeLogFile=${DR_SMOKE_LOG_FILE}"
    echo "smokeExitCode=${smoke_exit_code}"
    echo "rpoObservedSeconds=${rpo_observed_seconds}"
    echo "rpoTargetSeconds=${RPO_TARGET_SECONDS}"
    echo "rpoObjectiveMet=${objective_rpo_met}"
    echo "rtoObservedSeconds=${rto_observed_seconds}"
    echo "rtoTargetSeconds=${RTO_TARGET_SECONDS}"
    echo "rtoObjectiveMet=${objective_rto_met}"

    if [[ "${smoke_exit_code}" != "0" ]]; then
      echo "improvement=Corregir fallo del smoke DR antes de usar la metrica para decisiones operativas"
      echo "improvement=Ejecutar nuevamente el simulacro sobre entorno estable y registrar nueva evidencia"
    else
      if [[ "${objective_rpo_met}" != "true" ]]; then
        echo "improvement=Reducir ventana de backup (mayor frecuencia o backup previo a cambios criticos)"
      fi
      if [[ "${objective_rto_met}" != "true" ]]; then
        echo "improvement=Reducir tiempo de restauracion con prewarm de infraestructura y runbook de arranque rapido"
      fi
      if [[ "${objective_rpo_met}" == "true" && "${objective_rto_met}" == "true" ]]; then
        echo "improvement=Mantener cadencia de simulacro y objetivos actuales; no se observan desviaciones"
      fi
    fi
  } > "${DR_REPORT_FILE}"

  echo "[dr-drill] Report generated: ${DR_REPORT_FILE}"
}

if [[ ! "${RPO_TARGET_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "[dr-drill] ERROR RPO_TARGET_SECONDS must be a non-negative integer"
  exit 20
fi

if [[ ! "${RTO_TARGET_SECONDS}" =~ ^[0-9]+$ ]]; then
  echo "[dr-drill] ERROR RTO_TARGET_SECONDS must be a non-negative integer"
  exit 20
fi

require_command docker
require_command tee

if [[ -z "${DR_CYCLE_REPORT_FILE}" ]]; then
  DR_CYCLE_REPORT_FILE="$(find_latest_cycle_report)"
fi

if [[ -z "${DR_CYCLE_REPORT_FILE}" || ! -f "${DR_CYCLE_REPORT_FILE}" ]]; then
  echo "[dr-drill] ERROR unable to locate backup cycle report"
  exit 20
fi

if [[ ! -x "${DR_SMOKE_SCRIPT}" ]]; then
  echo "[dr-drill] ERROR smoke script is not executable: ${DR_SMOKE_SCRIPT}"
  exit 20
fi

CYCLE_TIMESTAMP="$(read_report_value "${DR_CYCLE_REPORT_FILE}" "timestamp")"
CYCLE_ERRORS="$(read_report_value "${DR_CYCLE_REPORT_FILE}" "backupErrors")"
RESTORE_CHECK_STATUS="$(read_report_value "${DR_CYCLE_REPORT_FILE}" "restoreCheckStatus")"

if [[ -z "${CYCLE_TIMESTAMP}" ]]; then
  echo "[dr-drill] ERROR cycle report missing timestamp: ${DR_CYCLE_REPORT_FILE}"
  exit 20
fi

if [[ "${CYCLE_ERRORS:-}" != "0" ]]; then
  echo "[dr-drill] ERROR cycle report contains backup errors (${CYCLE_ERRORS})"
  exit 20
fi

if [[ "${RESTORE_CHECK_STATUS:-}" != "ok" && "${RESTORE_CHECK_STATUS:-}" != "disabled" ]]; then
  echo "[dr-drill] ERROR cycle report restore integrity status is not acceptable (${RESTORE_CHECK_STATUS})"
  exit 20
fi

CYCLE_EPOCH="$(timestamp_to_epoch "${CYCLE_TIMESTAMP}")"
DR_START_EPOCH="$(/bin/date +%s)"
RPO_OBSERVED_SECONDS=$((DR_START_EPOCH - CYCLE_EPOCH))
if [[ ${RPO_OBSERVED_SECONDS} -lt 0 ]]; then
  RPO_OBSERVED_SECONDS=0
fi

/bin/mkdir -p "$(/usr/bin/dirname "${DR_SMOKE_LOG_FILE}")"

echo "[dr-drill] Using cycle report: ${DR_CYCLE_REPORT_FILE}"
echo "[dr-drill] RPO observed at drill start: ${RPO_OBSERVED_SECONDS}s"
echo "[dr-drill] Running smoke: ${DR_SMOKE_SCRIPT} ${DR_SMOKE_ARGS}"

SMOKE_START_EPOCH="$(/bin/date +%s)"
SMOKE_EXIT_CODE=0

set +e
if [[ -n "${DR_SMOKE_ARGS}" ]]; then
  # shellcheck disable=SC2086
  { "${DR_SMOKE_SCRIPT}" ${DR_SMOKE_ARGS}; } 2>&1 | /usr/bin/tee "${DR_SMOKE_LOG_FILE}"
  SMOKE_EXIT_CODE=$?
else
  { "${DR_SMOKE_SCRIPT}"; } 2>&1 | /usr/bin/tee "${DR_SMOKE_LOG_FILE}"
  SMOKE_EXIT_CODE=$?
fi
set -e

SMOKE_END_EPOCH="$(/bin/date +%s)"
RTO_OBSERVED_SECONDS=$((SMOKE_END_EPOCH - SMOKE_START_EPOCH))

OBJECTIVE_RPO_MET="false"
OBJECTIVE_RTO_MET="false"

if [[ ${RPO_OBSERVED_SECONDS} -le ${RPO_TARGET_SECONDS} ]]; then
  OBJECTIVE_RPO_MET="true"
fi

if [[ ${RTO_OBSERVED_SECONDS} -le ${RTO_TARGET_SECONDS} ]]; then
  OBJECTIVE_RTO_MET="true"
fi

write_report "${OBJECTIVE_RPO_MET}" "${OBJECTIVE_RTO_MET}" "${SMOKE_EXIT_CODE}" "${CYCLE_TIMESTAMP}" "${RPO_OBSERVED_SECONDS}" "${RTO_OBSERVED_SECONDS}"

echo "[dr-drill] RTO observed: ${RTO_OBSERVED_SECONDS}s"
echo "[dr-drill] RPO objective met: ${OBJECTIVE_RPO_MET} (target ${RPO_TARGET_SECONDS}s)"
echo "[dr-drill] RTO objective met: ${OBJECTIVE_RTO_MET} (target ${RTO_TARGET_SECONDS}s)"

if [[ ${SMOKE_EXIT_CODE} -ne 0 ]]; then
  echo "[dr-drill] FAILED: DR smoke failed"
  exit 20
fi

if [[ "${OBJECTIVE_RPO_MET}" == "true" && "${OBJECTIVE_RTO_MET}" == "true" ]]; then
  echo "[dr-drill] SUCCESS: DR drill finished within objectives"
  exit 0
fi

echo "[dr-drill] WARN: DR drill completed but one or more objectives were missed"
exit 10
