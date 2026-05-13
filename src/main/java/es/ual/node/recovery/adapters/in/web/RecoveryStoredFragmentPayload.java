package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import java.time.Instant;

/** HTTP response payload representing stored fragment metadata. */
public final class RecoveryStoredFragmentPayload {

  private final String fragmentId;
  private final String agreementId;
  private final String requesterNodeId;
  private final String checksumAlgorithm;
  private final String checksum;
  private final int sizeBytes;
  private final Instant storedAt;
  private final String status;

  private RecoveryStoredFragmentPayload(
      final String fragmentId,
      final String agreementId,
      final String requesterNodeId,
      final String checksumAlgorithm,
      final String checksum,
      final int sizeBytes,
      final Instant storedAt,
      final String status) {
    this.fragmentId = fragmentId;
    this.agreementId = agreementId;
    this.requesterNodeId = requesterNodeId;
    this.checksumAlgorithm = checksumAlgorithm;
    this.checksum = checksum;
    this.sizeBytes = sizeBytes;
    this.storedAt = storedAt;
    this.status = status;
  }

  /**
   * Creates response from stored domain object.
   *
   * @param stored stored fragment metadata
   * @return payload
   */
  public static RecoveryStoredFragmentPayload fromDomain(final RecoveryOrphanFragment stored) {
    return new RecoveryStoredFragmentPayload(
        stored.fragmentId(),
        stored.agreementId(),
        stored.requesterNodeId(),
        stored.checksumAlgorithm(),
        stored.checksum(),
        stored.sizeBytes(),
        stored.storedAt(),
        "STORED");
  }

  public String getFragmentId() {
    return fragmentId;
  }

  public String getAgreementId() {
    return agreementId;
  }

  public String getRequesterNodeId() {
    return requesterNodeId;
  }

  public String getChecksumAlgorithm() {
    return checksumAlgorithm;
  }

  public String getChecksum() {
    return checksum;
  }

  public int getSizeBytes() {
    return sizeBytes;
  }

  public Instant getStoredAt() {
    return storedAt;
  }

  public String getStatus() {
    return status;
  }
}
