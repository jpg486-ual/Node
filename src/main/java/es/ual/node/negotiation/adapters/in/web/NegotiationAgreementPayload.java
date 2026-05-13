package es.ual.node.negotiation.adapters.in.web;

import es.ual.node.negotiation.domain.NegotiationAgreement;
import java.time.Instant;

/** HTTP payload representing agreement state. */
public final class NegotiationAgreementPayload {

  private final String agreementId;
  private final String requesterNodeId;
  private final String targetNodeId;
  private final String status;
  private final String transferMode;
  private final long bucketSize;
  private final long expectedStorageBytes;
  private final Integer fragmentCount;
  private final String redundancyScheme;
  private final long plannedReservationBytes;
  private final Instant createdAt;
  private final Instant expiresAt;
  private final String requesterSignature;
  private final String targetSignature;
  private final String transferAuthorizationToken;
  private final String requesterTutorNodeId;
  private final String requesterTutorBaseUrl;

  /**
   * Creates payload instance.
   *
   * @param agreementId agreement id
   * @param requesterNodeId requester node id
   * @param targetNodeId target node id
   * @param status status
   * @param transferMode transfer mode
   * @param bucketSize bucket size
   * @param expectedStorageBytes expected storage bytes
   * @param fragmentCount fragment count
   * @param redundancyScheme redundancy scheme
   * @param plannedReservationBytes planned reservation bytes
   * @param createdAt creation timestamp
   * @param expiresAt expiration timestamp
   * @param requesterSignature requester signature
   * @param targetSignature target signature
   * @param transferAuthorizationToken transfer authorization token
   * @param requesterTutorNodeId optional requester tutor node id
   * @param requesterTutorBaseUrl optional requester tutor base URL
   */
  public NegotiationAgreementPayload(
      final String agreementId,
      final String requesterNodeId,
      final String targetNodeId,
      final String status,
      final String transferMode,
      final long bucketSize,
      final long expectedStorageBytes,
      final Integer fragmentCount,
      final String redundancyScheme,
      final long plannedReservationBytes,
      final Instant createdAt,
      final Instant expiresAt,
      final String requesterSignature,
      final String targetSignature,
      final String transferAuthorizationToken,
      final String requesterTutorNodeId,
      final String requesterTutorBaseUrl) {
    this.agreementId = agreementId;
    this.requesterNodeId = requesterNodeId;
    this.targetNodeId = targetNodeId;
    this.status = status;
    this.transferMode = transferMode;
    this.bucketSize = bucketSize;
    this.expectedStorageBytes = expectedStorageBytes;
    this.fragmentCount = fragmentCount;
    this.redundancyScheme = redundancyScheme;
    this.plannedReservationBytes = plannedReservationBytes;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.requesterSignature = requesterSignature;
    this.targetSignature = targetSignature;
    this.transferAuthorizationToken = transferAuthorizationToken;
    this.requesterTutorNodeId = requesterTutorNodeId;
    this.requesterTutorBaseUrl = requesterTutorBaseUrl;
  }

  /**
   * Converts domain agreement to payload.
   *
   * <p>El {@code transferAuthorizationToken} se persiste internamente pero NO se expone en el
   * payload JSON: ningún flujo productivo lo valida, vestigio del modelo simétrico abierto. El
   * campo se devuelve siempre {@code null} hasta que algún caller real lo necesite.
   *
   * @param agreement domain agreement
   * @return payload
   */
  public static NegotiationAgreementPayload fromDomain(final NegotiationAgreement agreement) {
    return new NegotiationAgreementPayload(
        agreement.agreementId(),
        agreement.requesterNodeId(),
        agreement.targetNodeId(),
        agreement.status().name(),
        agreement.transferMode().name(),
        agreement.bucketSize(),
        agreement.expectedStorageBytes(),
        agreement.fragmentCount(),
        agreement.redundancyScheme(),
        agreement.plannedReservationBytes(),
        agreement.createdAt(),
        agreement.expiresAt(),
        agreement.requesterSignature(),
        agreement.targetSignature(),
        null,
        agreement.requesterTutorNodeId(),
        agreement.requesterTutorBaseUrl());
  }

  /**
   * Returns agreement id.
   *
   * @return agreement id
   */
  public String agreementId() {
    return agreementId;
  }

  /**
   * Returns agreement id using JavaBean convention.
   *
   * @return agreement id
   */
  public String getAgreementId() {
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
   * Returns requester node id using JavaBean convention.
   *
   * @return requester node id
   */
  public String getRequesterNodeId() {
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
   * Returns target node id using JavaBean convention.
   *
   * @return target node id
   */
  public String getTargetNodeId() {
    return targetNodeId;
  }

  /**
   * Returns status.
   *
   * @return status
   */
  public String status() {
    return status;
  }

  /**
   * Returns status using JavaBean convention.
   *
   * @return status
   */
  public String getStatus() {
    return status;
  }

  /**
   * Returns transfer mode.
   *
   * @return transfer mode
   */
  public String transferMode() {
    return transferMode;
  }

  /**
   * Returns transfer mode using JavaBean convention.
   *
   * @return transfer mode
   */
  public String getTransferMode() {
    return transferMode;
  }

  /**
   * Returns bucket size.
   *
   * @return bucket size
   */
  public long bucketSize() {
    return bucketSize;
  }

  /**
   * Returns bucket size using JavaBean convention.
   *
   * @return bucket size
   */
  public long getBucketSize() {
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
   * Returns expected storage bytes using JavaBean convention.
   *
   * @return expected storage bytes
   */
  public long getExpectedStorageBytes() {
    return expectedStorageBytes;
  }

  /**
   * Returns fragment count.
   *
   * @return fragment count
   */
  public Integer fragmentCount() {
    return fragmentCount;
  }

  /**
   * Returns fragment count using JavaBean convention.
   *
   * @return fragment count
   */
  public Integer getFragmentCount() {
    return fragmentCount;
  }

  /**
   * Returns redundancy scheme.
   *
   * @return redundancy scheme
   */
  public String redundancyScheme() {
    return redundancyScheme;
  }

  /**
   * Returns redundancy scheme using JavaBean convention.
   *
   * @return redundancy scheme
   */
  public String getRedundancyScheme() {
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
   * Returns planned reservation bytes using JavaBean convention.
   *
   * @return planned reservation bytes
   */
  public long getPlannedReservationBytes() {
    return plannedReservationBytes;
  }

  /**
   * Returns createdAt.
   *
   * @return createdAt
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * Returns createdAt using JavaBean convention.
   *
   * @return createdAt
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Returns expiresAt.
   *
   * @return expiresAt
   */
  public Instant expiresAt() {
    return expiresAt;
  }

  /**
   * Returns expiresAt using JavaBean convention.
   *
   * @return expiresAt
   */
  public Instant getExpiresAt() {
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
   * Returns requester signature using JavaBean convention.
   *
   * @return requester signature
   */
  public String getRequesterSignature() {
    return requesterSignature;
  }

  /**
   * Returns target signature.
   *
   * @return target signature
   */
  public String targetSignature() {
    return targetSignature;
  }

  /**
   * Returns target signature using JavaBean convention.
   *
   * @return target signature
   */
  public String getTargetSignature() {
    return targetSignature;
  }

  /**
   * Returns transfer authorization token.
   *
   * @return transfer authorization token
   */
  public String transferAuthorizationToken() {
    return transferAuthorizationToken;
  }

  /**
   * Returns transfer authorization token using JavaBean convention.
   *
   * @return transfer authorization token
   */
  public String getTransferAuthorizationToken() {
    return transferAuthorizationToken;
  }

  /**
   * Returns optional requester tutor node id.
   *
   * @return requester tutor node id or {@code null}
   */
  public String requesterTutorNodeId() {
    return requesterTutorNodeId;
  }

  /**
   * Returns optional requester tutor node id using JavaBean convention.
   *
   * @return requester tutor node id or {@code null}
   */
  public String getRequesterTutorNodeId() {
    return requesterTutorNodeId;
  }

  /**
   * Returns optional requester tutor base URL.
   *
   * @return requester tutor base URL or {@code null}
   */
  public String requesterTutorBaseUrl() {
    return requesterTutorBaseUrl;
  }

  /**
   * Returns optional requester tutor base URL using JavaBean convention.
   *
   * @return requester tutor base URL or {@code null}
   */
  public String getRequesterTutorBaseUrl() {
    return requesterTutorBaseUrl;
  }
}
