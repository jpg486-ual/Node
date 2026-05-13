package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA entity for the <strong>recovery domain</strong>. Maps to {@code recovery_orphan_fragment}.
 * Tutor holds orphan fragments returned by peers via RETURN_TO_TUTOR escalation when the requester
 * is detected unresponsive.
 */
@Entity
@Table(name = "recovery_orphan_fragment")
public class RecoveryOrphanFragmentJpaEntity {

  @Id
  @Column(name = "fragment_id", nullable = false, length = 128)
  private String fragmentId;

  @Column(name = "agreement_id", nullable = false, length = 128)
  private String agreementId;

  @Column(name = "requester_node_id", nullable = false, length = 128)
  private String requesterNodeId;

  @Column(name = "checksum_algorithm", nullable = false, length = 64)
  private String checksumAlgorithm;

  @Column(name = "checksum", nullable = false, length = 512)
  private String checksum;

  @Column(name = "size_bytes", nullable = false)
  private Integer sizeBytes;

  @Column(name = "stored_at", nullable = false)
  private Instant storedAt;

  public String getFragmentId() {
    return fragmentId;
  }

  public void setFragmentId(final String fragmentId) {
    this.fragmentId = fragmentId;
  }

  public String getAgreementId() {
    return agreementId;
  }

  public void setAgreementId(final String agreementId) {
    this.agreementId = agreementId;
  }

  public String getRequesterNodeId() {
    return requesterNodeId;
  }

  public void setRequesterNodeId(final String requesterNodeId) {
    this.requesterNodeId = requesterNodeId;
  }

  public String getChecksumAlgorithm() {
    return checksumAlgorithm;
  }

  public void setChecksumAlgorithm(final String checksumAlgorithm) {
    this.checksumAlgorithm = checksumAlgorithm;
  }

  public String getChecksum() {
    return checksum;
  }

  public void setChecksum(final String checksum) {
    this.checksum = checksum;
  }

  public Integer getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(final Integer sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public Instant getStoredAt() {
    return storedAt;
  }

  public void setStoredAt(final Instant storedAt) {
    this.storedAt = storedAt;
  }
}
