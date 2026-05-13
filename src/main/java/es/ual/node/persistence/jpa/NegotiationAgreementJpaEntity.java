package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for negotiation agreements. */
@Entity
@Table(name = "negotiation_agreement")
public class NegotiationAgreementJpaEntity {

  @Id
  @Column(name = "agreement_id", nullable = false, length = 128)
  private String agreementId;

  @Column(name = "requester_node_id", nullable = false, length = 128)
  private String requesterNodeId;

  @Column(name = "target_node_id", nullable = false, length = 128)
  private String targetNodeId;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "transfer_mode", nullable = false, length = 64)
  private String transferMode;

  @Column(name = "bucket_size", nullable = false)
  private long bucketSize;

  @Column(name = "expected_storage_bytes", nullable = false)
  private long expectedStorageBytes;

  @Column(name = "fragment_count")
  private Integer fragmentCount;

  @Column(name = "redundancy_scheme", length = 128)
  private String redundancyScheme;

  @Column(name = "planned_reservation_bytes", nullable = false)
  private long plannedReservationBytes;

  @Column(name = "file_id", length = 64)
  private String fileId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "requester_signature", nullable = false, columnDefinition = "TEXT")
  private String requesterSignature;

  @Column(name = "target_signature", columnDefinition = "TEXT")
  private String targetSignature;

  @Column(name = "transfer_token", length = 256)
  private String transferToken;

  @Column(name = "transfer_token_issued_at")
  private Instant transferTokenIssuedAt;

  @Column(name = "transfer_token_expires_at")
  private Instant transferTokenExpiresAt;

  @Column(name = "requester_tutor_node_id", length = 128)
  private String requesterTutorNodeId;

  @Column(name = "requester_tutor_base_url", length = 256)
  private String requesterTutorBaseUrl;

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

  public String getTargetNodeId() {
    return targetNodeId;
  }

  public void setTargetNodeId(final String targetNodeId) {
    this.targetNodeId = targetNodeId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(final String status) {
    this.status = status;
  }

  public String getTransferMode() {
    return transferMode;
  }

  public void setTransferMode(final String transferMode) {
    this.transferMode = transferMode;
  }

  public long getBucketSize() {
    return bucketSize;
  }

  public void setBucketSize(final long bucketSize) {
    this.bucketSize = bucketSize;
  }

  public long getExpectedStorageBytes() {
    return expectedStorageBytes;
  }

  public void setExpectedStorageBytes(final long expectedStorageBytes) {
    this.expectedStorageBytes = expectedStorageBytes;
  }

  public Integer getFragmentCount() {
    return fragmentCount;
  }

  public void setFragmentCount(final Integer fragmentCount) {
    this.fragmentCount = fragmentCount;
  }

  public String getRedundancyScheme() {
    return redundancyScheme;
  }

  public void setRedundancyScheme(final String redundancyScheme) {
    this.redundancyScheme = redundancyScheme;
  }

  public long getPlannedReservationBytes() {
    return plannedReservationBytes;
  }

  public void setPlannedReservationBytes(final long plannedReservationBytes) {
    this.plannedReservationBytes = plannedReservationBytes;
  }

  public String getFileId() {
    return fileId;
  }

  public void setFileId(final String fileId) {
    this.fileId = fileId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(final Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getRequesterSignature() {
    return requesterSignature;
  }

  public void setRequesterSignature(final String requesterSignature) {
    this.requesterSignature = requesterSignature;
  }

  public String getTargetSignature() {
    return targetSignature;
  }

  public void setTargetSignature(final String targetSignature) {
    this.targetSignature = targetSignature;
  }

  public String getTransferToken() {
    return transferToken;
  }

  public void setTransferToken(final String transferToken) {
    this.transferToken = transferToken;
  }

  public Instant getTransferTokenIssuedAt() {
    return transferTokenIssuedAt;
  }

  public void setTransferTokenIssuedAt(final Instant transferTokenIssuedAt) {
    this.transferTokenIssuedAt = transferTokenIssuedAt;
  }

  public Instant getTransferTokenExpiresAt() {
    return transferTokenExpiresAt;
  }

  public void setTransferTokenExpiresAt(final Instant transferTokenExpiresAt) {
    this.transferTokenExpiresAt = transferTokenExpiresAt;
  }

  public String getRequesterTutorNodeId() {
    return requesterTutorNodeId;
  }

  public void setRequesterTutorNodeId(final String requesterTutorNodeId) {
    this.requesterTutorNodeId = requesterTutorNodeId;
  }

  public String getRequesterTutorBaseUrl() {
    return requesterTutorBaseUrl;
  }

  public void setRequesterTutorBaseUrl(final String requesterTutorBaseUrl) {
    this.requesterTutorBaseUrl = requesterTutorBaseUrl;
  }
}
