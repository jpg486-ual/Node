#!/usr/bin/env bash
# Verifica supply-chain: SHA-256 del JAR + POM de la dependencia JavaReedSolomon
# en el repo Maven local contra los hashes esperados (versión congelada en el
# pom del proyecto). Pensado como gate de CI para detectar dependency-confusion
# o JAR sustituidos.
#
# Uso:
#   ./scripts/ops/verify-reedsolomon-supply-chain.sh
#   EXPECTED_VERSION=<git-sha> EXPECTED_JAR_SHA256=<sha> ./scripts/ops/...
#
# Salidas:
#   exit 0       JAR + POM SHA-256 coinciden con lo esperado
#   exit !=0     mismatch o artefactos no resolvibles desde Maven Central/local

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

EXPECTED_VERSION="${EXPECTED_VERSION:-d3c481dc69}"
EXPECTED_JAR_SHA256="${EXPECTED_JAR_SHA256:-cad9a629ef4734501974082a62e3da5dba998c99ff9ec78633c656200c9314f0}"
EXPECTED_POM_SHA256="${EXPECTED_POM_SHA256:-c04ad4f14f1afacfeaeff5b4ad7d0b27c0b42b6eeba6bd0576380b7de06ffb41}"
M2_REPO="${M2_REPO:-${HOME}/.m2/repository}"

ARTIFACT_BASE_DIR="${M2_REPO}/com/github/Backblaze/JavaReedSolomon/${EXPECTED_VERSION}"
JAR_PATH="${ARTIFACT_BASE_DIR}/JavaReedSolomon-${EXPECTED_VERSION}.jar"
POM_PATH="${ARTIFACT_BASE_DIR}/JavaReedSolomon-${EXPECTED_VERSION}.pom"

require_command() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "[verify-rs-supply-chain] ERROR: command not found: ${cmd}"
    exit 1
  fi
}

sha256_file() {
  local target_file="$1"
  shasum -a 256 "${target_file}" | awk '{print $1}'
}

ensure_artifact_resolved() {
  if [[ -f "${JAR_PATH}" && -f "${POM_PATH}" ]]; then
    return
  fi

  echo "[verify-rs-supply-chain] Resolviendo artefacto Backblaze desde JitPack"
  (
    cd "${PROJECT_ROOT}"
    ./mvnw -q dependency:get \
      -Dartifact="com.github.Backblaze:JavaReedSolomon:${EXPECTED_VERSION}" \
      -DremoteRepositories="jitpack::default::https://jitpack.io"
  )
}

main() {
  require_command shasum
  require_command awk

  ensure_artifact_resolved

  if [[ ! -f "${JAR_PATH}" ]]; then
    echo "[verify-rs-supply-chain] ERROR: no se encontró jar: ${JAR_PATH}"
    exit 1
  fi

  if [[ ! -f "${POM_PATH}" ]]; then
    echo "[verify-rs-supply-chain] ERROR: no se encontró pom: ${POM_PATH}"
    exit 1
  fi

  local actual_jar_sha256
  local actual_pom_sha256
  actual_jar_sha256="$(sha256_file "${JAR_PATH}")"
  actual_pom_sha256="$(sha256_file "${POM_PATH}")"

  echo "[verify-rs-supply-chain] EXPECTED_VERSION=${EXPECTED_VERSION}"
  echo "[verify-rs-supply-chain] JAR_SHA256(actual)=${actual_jar_sha256}"
  echo "[verify-rs-supply-chain] POM_SHA256(actual)=${actual_pom_sha256}"

  if [[ "${actual_jar_sha256}" != "${EXPECTED_JAR_SHA256}" ]]; then
    echo "[verify-rs-supply-chain] ERROR: SHA-256 del jar no coincide"
    echo "[verify-rs-supply-chain] expected=${EXPECTED_JAR_SHA256}"
    echo "[verify-rs-supply-chain] actual=${actual_jar_sha256}"
    exit 1
  fi

  if [[ "${actual_pom_sha256}" != "${EXPECTED_POM_SHA256}" ]]; then
    echo "[verify-rs-supply-chain] ERROR: SHA-256 del pom no coincide"
    echo "[verify-rs-supply-chain] expected=${EXPECTED_POM_SHA256}"
    echo "[verify-rs-supply-chain] actual=${actual_pom_sha256}"
    exit 1
  fi

  echo "[verify-rs-supply-chain] OK: integridad y versión verificadas"
}

main "$@"
