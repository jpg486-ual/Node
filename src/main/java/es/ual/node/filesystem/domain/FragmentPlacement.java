package es.ual.node.filesystem.domain;

import java.time.Instant;

/**
 * Immutable record of where a Reed-Solomon fragment of a user's file was placed by the origin
 * during the upload distribution flow; extended with {@code blockIndex} for RS por bloques;
 * extended with persistent health state.
 *
 * <p>Used by the origin node to reconstruct the file at download time: the manifest tells the
 * origin which blocks and fragments make up the file (their indices and hashes), and the placement
 * records tell it which custodian holds each one and at which base URL to fetch it. One row per
 * fragment per block.
 *
 * @param fileId backing manifest file identifier
 * @param fragmentId fragment identifier
 * @param blockIndex zero-based index of the RS block this fragment belongs to. Legacy single-block
 *     placements use {@code 0}.
 * @param fragmentIndex zero-based index within the RS scheme of the block
 * @param parity {@code true} for parity (redundancy) fragments, {@code false} for data fragments
 * @param custodianNodeId node identifier of the custodian holding the fragment
 * @param custodianBaseUrl HTTP base URL of the custodian (used to fetch the fragment back)
 * @param agreementId opaque identifier shared with the custodian's storage record (allows
 *     correlation between origin placement and custodian custody)
 * @param fragmentChecksum SHA-256 of the fragment payload, lowercase hex
 * @param fragmentSizeBytes payload size in bytes
 * @param createdAt placement timestamp
 * @param healthStatus estado de salud desde la vista del origen. Default {@link
 *     FragmentHealthStatus#OK} en upload normal; transiciones operadas por el inbound probe handler
 *     y el {@code OriginInverseProbeWorker}
 * @param lastCheckAt timestamp del último probe (entrante o saliente) que validó este placement.
 *     {@code null} hasta el primer probe
 * @param consecutiveFailures contador de intentos consecutivos sin respuesta del custodian (probe
 *     inverso). Reset a {@code 0} cuando el custodian responde. Cuando alcanza el umbral
 *     configurable, transiciona a {@link FragmentHealthStatus#PERDIDO}
 */
public record FragmentPlacement(
    String fileId,
    String fragmentId,
    int blockIndex,
    int fragmentIndex,
    boolean parity,
    String custodianNodeId,
    String custodianBaseUrl,
    String agreementId,
    String fragmentChecksum,
    long fragmentSizeBytes,
    Instant createdAt,
    FragmentHealthStatus healthStatus,
    Instant lastCheckAt,
    int consecutiveFailures) {

  public FragmentPlacement {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be blank");
    }
    if (fragmentId == null || fragmentId.isBlank()) {
      throw new IllegalArgumentException("fragmentId must not be blank");
    }
    if (blockIndex < 0) {
      throw new IllegalArgumentException("blockIndex must not be negative");
    }
    if (fragmentIndex < 0) {
      throw new IllegalArgumentException("fragmentIndex must not be negative");
    }
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      throw new IllegalArgumentException("custodianNodeId must not be blank");
    }
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      throw new IllegalArgumentException("custodianBaseUrl must not be blank");
    }
    if (agreementId == null || agreementId.isBlank()) {
      throw new IllegalArgumentException("agreementId must not be blank");
    }
    if (fragmentChecksum == null || fragmentChecksum.isBlank()) {
      throw new IllegalArgumentException("fragmentChecksum must not be blank");
    }
    if (fragmentSizeBytes <= 0) {
      throw new IllegalArgumentException("fragmentSizeBytes must be greater than zero");
    }
    if (createdAt == null) {
      throw new IllegalArgumentException("createdAt must not be null");
    }
    if (healthStatus == null) {
      throw new IllegalArgumentException("healthStatus must not be null");
    }
    if (consecutiveFailures < 0) {
      throw new IllegalArgumentException("consecutiveFailures must not be negative");
    }
    fileId = fileId.trim();
    fragmentId = fragmentId.trim();
    custodianNodeId = custodianNodeId.trim();
    custodianBaseUrl = custodianBaseUrl.trim();
    agreementId = agreementId.trim();
    fragmentChecksum = fragmentChecksum.trim().toLowerCase();
  }

  /**
   * Constructor de 11 args: defaults {@code healthStatus=OK, lastCheckAt=null,
   * consecutiveFailures=0}. Aplicado en el upload flow original que crea placements frescos sin
   * probes históricos.
   */
  public FragmentPlacement(
      final String fileId,
      final String fragmentId,
      final int blockIndex,
      final int fragmentIndex,
      final boolean parity,
      final String custodianNodeId,
      final String custodianBaseUrl,
      final String agreementId,
      final String fragmentChecksum,
      final long fragmentSizeBytes,
      final Instant createdAt) {
    this(
        fileId,
        fragmentId,
        blockIndex,
        fragmentIndex,
        parity,
        custodianNodeId,
        custodianBaseUrl,
        agreementId,
        fragmentChecksum,
        fragmentSizeBytes,
        createdAt,
        FragmentHealthStatus.OK,
        null,
        0);
  }

  /**
   * Legacy 10-arg constructor: defaults {@code blockIndex} to zero. Kept for tests and any caller
   * that hasn't migrated to the per-block layout yet.
   */
  public FragmentPlacement(
      final String fileId,
      final String fragmentId,
      final int fragmentIndex,
      final boolean parity,
      final String custodianNodeId,
      final String custodianBaseUrl,
      final String agreementId,
      final String fragmentChecksum,
      final long fragmentSizeBytes,
      final Instant createdAt) {
    this(
        fileId,
        fragmentId,
        0,
        fragmentIndex,
        parity,
        custodianNodeId,
        custodianBaseUrl,
        agreementId,
        fragmentChecksum,
        fragmentSizeBytes,
        createdAt,
        FragmentHealthStatus.OK,
        null,
        0);
  }

  /**
   * Devuelve un placement con el {@code healthStatus} actualizado (transición de estado por probe).
   * Si la transición es a {@link FragmentHealthStatus#OK}, resetea {@code consecutiveFailures} a 0.
   *
   * @param status nuevo estado de salud
   * @param at timestamp del check que produjo la transición
   * @return nuevo placement
   */
  public FragmentPlacement withHealth(final FragmentHealthStatus status, final Instant at) {
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    final int nextFailures = status == FragmentHealthStatus.OK ? 0 : consecutiveFailures;
    return new FragmentPlacement(
        fileId,
        fragmentId,
        blockIndex,
        fragmentIndex,
        parity,
        custodianNodeId,
        custodianBaseUrl,
        agreementId,
        fragmentChecksum,
        fragmentSizeBytes,
        createdAt,
        status,
        at,
        nextFailures);
  }

  /**
   * Devuelve un placement con {@code consecutiveFailures} incrementado y {@code healthStatus} a
   * {@link FragmentHealthStatus#EN_RIESGO}. Usado cuando el custodian no responde el probe inverso.
   * Si el contador alcanza o supera {@code unresponsiveThresholdAttempts} la transición salta
   * directamente a {@link FragmentHealthStatus#PERDIDO}.
   *
   * @param at timestamp del intento fallido
   * @param unresponsiveThresholdAttempts umbral configurable
   * @return nuevo placement
   */
  public FragmentPlacement withFailureIncremented(
      final Instant at, final int unresponsiveThresholdAttempts) {
    final int nextFailures = consecutiveFailures + 1;
    final FragmentHealthStatus nextStatus =
        nextFailures >= unresponsiveThresholdAttempts
            ? FragmentHealthStatus.PERDIDO
            : FragmentHealthStatus.EN_RIESGO;
    return new FragmentPlacement(
        fileId,
        fragmentId,
        blockIndex,
        fragmentIndex,
        parity,
        custodianNodeId,
        custodianBaseUrl,
        agreementId,
        fragmentChecksum,
        fragmentSizeBytes,
        createdAt,
        nextStatus,
        at,
        nextFailures);
  }
}
