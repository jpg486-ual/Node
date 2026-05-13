package es.ual.node.negotiation.domain;

import java.time.Instant;

/** Immutable agreement model authorizing future transfer operations. */
public final class NegotiationAgreement {

  private final String agreementId;
  private final String requesterNodeId;
  private final String targetNodeId;
  private final NegotiationStatus status;
  private final TransferMode transferMode;
  private final long bucketSize;
  private final long expectedStorageBytes;
  private final Integer fragmentCount;
  private final String redundancyScheme;
  private final long plannedReservationBytes;
  private final FileManifest fileManifest;
  // FileId derivado del manifest en construcción canónica; el adapter
  // Postgres lo override via {@link #withFileId(String)} para preservar la referencia tras
  // round-trip cuando el blob fileManifest fue descartado.
  private final String fileId;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final String requesterSignature;
  private final String targetSignature;
  private final TransferAuthorizationToken transferAuthorizationToken;
  private final String requesterTutorNodeId;
  private final String requesterTutorBaseUrl;

  /**
   * Convenience constructor without requester tutor info. Delegates to the canonical constructor
   * with {@code null} tutor fields.
   *
   * @param agreementId agreement identifier
   * @param requesterNodeId requester node id
   * @param targetNodeId target node id
   * @param status agreement status
   * @param transferMode transfer mode
   * @param bucketSize negotiated bucket size
   * @param expectedStorageBytes expected storage bytes
   * @param fragmentCount optional fragment count
   * @param redundancyScheme optional redundancy scheme
   * @param plannedReservationBytes planned reservation bytes
   * @param fileManifest optional file manifest
   * @param createdAt creation timestamp
   * @param expiresAt expiration timestamp
   * @param requesterSignature requester signature
   * @param targetSignature target signature
   * @param transferAuthorizationToken transfer authorization token
   */
  public NegotiationAgreement(
      final String agreementId,
      final String requesterNodeId,
      final String targetNodeId,
      final NegotiationStatus status,
      final TransferMode transferMode,
      final long bucketSize,
      final long expectedStorageBytes,
      final Integer fragmentCount,
      final String redundancyScheme,
      final long plannedReservationBytes,
      final FileManifest fileManifest,
      final Instant createdAt,
      final Instant expiresAt,
      final String requesterSignature,
      final String targetSignature,
      final TransferAuthorizationToken transferAuthorizationToken) {
    this(
        agreementId,
        requesterNodeId,
        targetNodeId,
        status,
        transferMode,
        bucketSize,
        expectedStorageBytes,
        fragmentCount,
        redundancyScheme,
        plannedReservationBytes,
        fileManifest,
        createdAt,
        expiresAt,
        requesterSignature,
        targetSignature,
        transferAuthorizationToken,
        null,
        null);
  }

  /**
   * Creates a validated negotiation agreement.
   *
   * @param agreementId agreement identifier
   * @param requesterNodeId requester node id
   * @param targetNodeId target node id
   * @param status agreement status
   * @param transferMode transfer mode
   * @param bucketSize negotiated bucket size
   * @param expectedStorageBytes expected storage bytes
   * @param fragmentCount optional fragment count
   * @param redundancyScheme optional redundancy scheme
   * @param plannedReservationBytes planned reservation bytes
   * @param fileManifest optional file manifest
   * @param createdAt creation timestamp
   * @param expiresAt expiration timestamp
   * @param requesterSignature requester signature
   * @param targetSignature target signature
   * @param transferAuthorizationToken transfer authorization token
   * @param requesterTutorNodeId optional tutor node id of the requester (propagated for correct
   *     return-to-tutor escalation when this peer custodies fragments)
   * @param requesterTutorBaseUrl optional tutor base URL of the requester (propagated for correct
   *     return-to-tutor escalation when this peer custodies fragments)
   */
  public NegotiationAgreement(
      final String agreementId,
      final String requesterNodeId,
      final String targetNodeId,
      final NegotiationStatus status,
      final TransferMode transferMode,
      final long bucketSize,
      final long expectedStorageBytes,
      final Integer fragmentCount,
      final String redundancyScheme,
      final long plannedReservationBytes,
      final FileManifest fileManifest,
      final Instant createdAt,
      final Instant expiresAt,
      final String requesterSignature,
      final String targetSignature,
      final TransferAuthorizationToken transferAuthorizationToken,
      final String requesterTutorNodeId,
      final String requesterTutorBaseUrl) {
    if (agreementId == null || agreementId.isBlank()) {
      throw new IllegalArgumentException("agreementId must not be blank");
    }
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    if (targetNodeId == null || targetNodeId.isBlank()) {
      throw new IllegalArgumentException("targetNodeId must not be blank");
    }
    if (status == null || transferMode == null) {
      throw new IllegalArgumentException("status and transferMode must not be null");
    }
    if (bucketSize <= 0) {
      throw new IllegalArgumentException("bucketSize must be greater than zero");
    }
    if (expectedStorageBytes <= 0) {
      throw new IllegalArgumentException("expectedStorageBytes must be greater than zero");
    }
    if (plannedReservationBytes <= 0) {
      throw new IllegalArgumentException("plannedReservationBytes must be greater than zero");
    }
    if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("invalid timestamps");
    }
    if (requesterSignature == null || requesterSignature.isBlank()) {
      throw new IllegalArgumentException("requesterSignature must not be blank");
    }

    if (requesterTutorNodeId != null && requesterTutorNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterTutorNodeId must not be blank when provided");
    }
    if (requesterTutorBaseUrl != null && requesterTutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("requesterTutorBaseUrl must not be blank when provided");
    }

    this(
        agreementId,
        requesterNodeId,
        targetNodeId,
        status,
        transferMode,
        bucketSize,
        expectedStorageBytes,
        fragmentCount,
        redundancyScheme,
        plannedReservationBytes,
        fileManifest,
        // FileId derivado del manifest en este constructor canónico.
        fileManifest != null ? fileManifest.fileId() : null,
        createdAt,
        expiresAt,
        requesterSignature,
        targetSignature,
        transferAuthorizationToken,
        requesterTutorNodeId,
        requesterTutorBaseUrl);
  }

  /**
   * Constructor 19-args con {@code fileId} explícito. Se usa desde el adapter Postgres en {@code
   * toDomain()} cuando el {@code fileManifest} viene null pero la columna {@code file_id} preserva
   * la referencia.
   *
   * @param explicitFileId fileId persistido en la columna; {@code null} permitido cuando la
   *     reservación/agreement no estaba asociada a ningún FileManifest
   */
  public NegotiationAgreement(
      final String agreementId,
      final String requesterNodeId,
      final String targetNodeId,
      final NegotiationStatus status,
      final TransferMode transferMode,
      final long bucketSize,
      final long expectedStorageBytes,
      final Integer fragmentCount,
      final String redundancyScheme,
      final long plannedReservationBytes,
      final FileManifest fileManifest,
      final String explicitFileId,
      final Instant createdAt,
      final Instant expiresAt,
      final String requesterSignature,
      final String targetSignature,
      final TransferAuthorizationToken transferAuthorizationToken,
      final String requesterTutorNodeId,
      final String requesterTutorBaseUrl) {
    if (agreementId == null || agreementId.isBlank()) {
      throw new IllegalArgumentException("agreementId must not be blank");
    }
    if (requesterNodeId == null || requesterNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterNodeId must not be blank");
    }
    if (targetNodeId == null || targetNodeId.isBlank()) {
      throw new IllegalArgumentException("targetNodeId must not be blank");
    }
    if (status == null || transferMode == null) {
      throw new IllegalArgumentException("status and transferMode must not be null");
    }
    if (bucketSize <= 0) {
      throw new IllegalArgumentException("bucketSize must be greater than zero");
    }
    if (expectedStorageBytes <= 0) {
      throw new IllegalArgumentException("expectedStorageBytes must be greater than zero");
    }
    if (plannedReservationBytes <= 0) {
      throw new IllegalArgumentException("plannedReservationBytes must be greater than zero");
    }
    if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("invalid timestamps");
    }
    if (requesterSignature == null || requesterSignature.isBlank()) {
      throw new IllegalArgumentException("requesterSignature must not be blank");
    }

    if (requesterTutorNodeId != null && requesterTutorNodeId.isBlank()) {
      throw new IllegalArgumentException("requesterTutorNodeId must not be blank when provided");
    }
    if (requesterTutorBaseUrl != null && requesterTutorBaseUrl.isBlank()) {
      throw new IllegalArgumentException("requesterTutorBaseUrl must not be blank when provided");
    }

    this.agreementId = agreementId.trim();
    this.requesterNodeId = requesterNodeId.trim();
    this.targetNodeId = targetNodeId.trim();
    this.status = status;
    this.transferMode = transferMode;
    this.bucketSize = bucketSize;
    this.expectedStorageBytes = expectedStorageBytes;
    this.fragmentCount = fragmentCount;
    this.redundancyScheme = redundancyScheme;
    this.plannedReservationBytes = plannedReservationBytes;
    this.fileManifest = fileManifest;
    this.fileId =
        explicitFileId != null && !explicitFileId.isBlank() ? explicitFileId.trim() : null;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.requesterSignature = requesterSignature.trim();
    this.targetSignature = targetSignature == null ? null : targetSignature.trim();
    this.transferAuthorizationToken = transferAuthorizationToken;
    this.requesterTutorNodeId = requesterTutorNodeId == null ? null : requesterTutorNodeId.trim();
    this.requesterTutorBaseUrl =
        requesterTutorBaseUrl == null ? null : requesterTutorBaseUrl.trim();
  }

  /**
   * Returns agreement identifier.
   *
   * @return agreement identifier
   */
  public String agreementId() {
    return agreementId;
  }

  /**
   * Returns requester node id.
   *
   * @return requester node id
   */
  public String requesterNodeId() {
    return requesterNodeId;
  }

  /**
   * Returns target node id.
   *
   * @return target node id
   */
  public String targetNodeId() {
    return targetNodeId;
  }

  /**
   * Returns status.
   *
   * @return agreement status
   */
  public NegotiationStatus status() {
    return status;
  }

  /**
   * Returns transfer mode.
   *
   * @return transfer mode
   */
  public TransferMode transferMode() {
    return transferMode;
  }

  /**
   * Returns negotiated bucket size.
   *
   * @return bucket size
   */
  public long bucketSize() {
    return bucketSize;
  }

  /**
   * Returns expected storage bytes.
   *
   * @return expected storage bytes
   */
  public long expectedStorageBytes() {
    return expectedStorageBytes;
  }

  /**
   * Returns optional fragment count.
   *
   * @return fragment count or null
   */
  public Integer fragmentCount() {
    return fragmentCount;
  }

  /**
   * Returns redundancy scheme.
   *
   * @return redundancy scheme or null
   */
  public String redundancyScheme() {
    return redundancyScheme;
  }

  /**
   * Returns planned reservation bytes.
   *
   * @return planned reservation bytes
   */
  public long plannedReservationBytes() {
    return plannedReservationBytes;
  }

  /**
   * Returns file manifest when present.
   *
   * @return file manifest or null
   */
  public FileManifest fileManifest() {
    return fileManifest;
  }

  /**
   * Returns the file id of the manifest associated with this agreement. Derived from the {@link
   * #fileManifest()} when present; preserved as a standalone column post-load when the persistence
   * adapter discarded the manifest.
   *
   * @return file id or null when no manifest was associated with the agreement
   */
  public String fileId() {
    return fileId;
  }

  /**
   * Returns creation timestamp.
   *
   * @return creation timestamp
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * Returns expiration timestamp.
   *
   * @return expiration timestamp
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * Returns requester signature.
   *
   * @return requester signature
   */
  public String requesterSignature() {
    return requesterSignature;
  }

  /**
   * Returns target signature when present.
   *
   * @return target signature or null
   */
  public String targetSignature() {
    return targetSignature;
  }

  /**
   * Returns transfer authorization token when confirmed.
   *
   * @return transfer token or null
   */
  public TransferAuthorizationToken transferAuthorizationToken() {
    return transferAuthorizationToken;
  }

  /**
   * Indicates if agreement is terminal.
   *
   * @return {@code true} when status is terminal
   */
  public boolean isTerminal() {
    return status == NegotiationStatus.REJECTED
        || status == NegotiationStatus.CANCELLED
        || status == NegotiationStatus.EXPIRED;
  }

  /**
   * Indicates if agreement is expired at specific time.
   *
   * @param now instant to evaluate
   * @return {@code true} when expired
   */
  public boolean isExpiredAt(final Instant now) {
    return !expiresAt.isAfter(now);
  }

  /**
   * Returns a confirmed copy with target signature and transfer token.
   *
   * @param targetSignature target signature
   * @param token transfer authorization token
   * @return confirmed agreement copy
   */
  public NegotiationAgreement confirm(
      final String targetSignature, final TransferAuthorizationToken token) {
    return new NegotiationAgreement(
        agreementId,
        requesterNodeId,
        targetNodeId,
        NegotiationStatus.CONFIRMED,
        transferMode,
        bucketSize,
        expectedStorageBytes,
        fragmentCount,
        redundancyScheme,
        plannedReservationBytes,
        fileManifest,
        createdAt,
        expiresAt,
        requesterSignature,
        targetSignature,
        token,
        requesterTutorNodeId,
        requesterTutorBaseUrl);
  }

  /**
   * Returns rejected agreement copy.
   *
   * @return rejected agreement
   */
  public NegotiationAgreement reject() {
    return copyWithStatus(NegotiationStatus.REJECTED);
  }

  /**
   * Returns cancelled agreement copy.
   *
   * @return cancelled agreement
   */
  public NegotiationAgreement cancel() {
    return copyWithStatus(NegotiationStatus.CANCELLED);
  }

  /**
   * Returns expired agreement copy.
   *
   * @return expired agreement
   */
  public NegotiationAgreement expire() {
    return copyWithStatus(NegotiationStatus.EXPIRED);
  }

  private NegotiationAgreement copyWithStatus(final NegotiationStatus newStatus) {
    return new NegotiationAgreement(
        agreementId,
        requesterNodeId,
        targetNodeId,
        newStatus,
        transferMode,
        bucketSize,
        expectedStorageBytes,
        fragmentCount,
        redundancyScheme,
        plannedReservationBytes,
        fileManifest,
        createdAt,
        expiresAt,
        requesterSignature,
        targetSignature,
        transferAuthorizationToken,
        requesterTutorNodeId,
        requesterTutorBaseUrl);
  }

  /**
   * Returns optional tutor node id of the requester.
   *
   * @return requester tutor node id or {@code null} when absent
   */
  public String requesterTutorNodeId() {
    return requesterTutorNodeId;
  }

  /**
   * Returns optional tutor base URL of the requester.
   *
   * @return requester tutor base URL or {@code null} when absent
   */
  public String requesterTutorBaseUrl() {
    return requesterTutorBaseUrl;
  }
}
