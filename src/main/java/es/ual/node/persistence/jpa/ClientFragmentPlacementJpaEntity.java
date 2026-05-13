package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** JPA entity for client-side fragment placement records. */
@Entity
@Table(name = "client_fragment_placement")
@IdClass(ClientFragmentPlacementJpaEntity.PK.class)
public class ClientFragmentPlacementJpaEntity {

  @Id
  @Column(name = "file_id", nullable = false, length = 64)
  private String fileId;

  @Id
  @Column(name = "fragment_id", nullable = false, length = 64)
  private String fragmentId;

  @Column(name = "block_index", nullable = false)
  private int blockIndex;

  @Column(name = "fragment_index", nullable = false)
  private int fragmentIndex;

  @Column(name = "parity", nullable = false)
  private boolean parity;

  @Column(name = "custodian_node_id", nullable = false, length = 128)
  private String custodianNodeId;

  @Column(name = "custodian_base_url", nullable = false, length = 512)
  private String custodianBaseUrl;

  @Column(name = "agreement_id", nullable = false, length = 64)
  private String agreementId;

  @Column(name = "fragment_checksum", nullable = false, length = 128)
  private String fragmentChecksum;

  @Column(name = "fragment_size_bytes", nullable = false)
  private long fragmentSizeBytes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Estado de salud persistido. Default 'OK' en upload normal. */
  @Column(name = "health_status", nullable = false, length = 16)
  private String healthStatus = "OK";

  /** Timestamp del último probe (entrante o saliente) que validó el placement. */
  @Column(name = "last_check_at")
  private Instant lastCheckAt;

  /**
   * Contador de intentos consecutivos sin respuesta del custodian al probe inverso. Reset a 0
   * cuando el custodian responde. Cuando alcanza el umbral, transición a PERDIDO.
   */
  @Column(name = "consecutive_failures", nullable = false)
  private int consecutiveFailures;

  public String getHealthStatus() {
    return healthStatus;
  }

  public void setHealthStatus(final String healthStatus) {
    this.healthStatus = healthStatus;
  }

  public Instant getLastCheckAt() {
    return lastCheckAt;
  }

  public void setLastCheckAt(final Instant lastCheckAt) {
    this.lastCheckAt = lastCheckAt;
  }

  public int getConsecutiveFailures() {
    return consecutiveFailures;
  }

  public void setConsecutiveFailures(final int consecutiveFailures) {
    this.consecutiveFailures = consecutiveFailures;
  }

  public String getFileId() {
    return fileId;
  }

  public void setFileId(final String fileId) {
    this.fileId = fileId;
  }

  public String getFragmentId() {
    return fragmentId;
  }

  public void setFragmentId(final String fragmentId) {
    this.fragmentId = fragmentId;
  }

  public int getBlockIndex() {
    return blockIndex;
  }

  public void setBlockIndex(final int blockIndex) {
    this.blockIndex = blockIndex;
  }

  public int getFragmentIndex() {
    return fragmentIndex;
  }

  public void setFragmentIndex(final int fragmentIndex) {
    this.fragmentIndex = fragmentIndex;
  }

  public boolean isParity() {
    return parity;
  }

  public void setParity(final boolean parity) {
    this.parity = parity;
  }

  public String getCustodianNodeId() {
    return custodianNodeId;
  }

  public void setCustodianNodeId(final String custodianNodeId) {
    this.custodianNodeId = custodianNodeId;
  }

  public String getCustodianBaseUrl() {
    return custodianBaseUrl;
  }

  public void setCustodianBaseUrl(final String custodianBaseUrl) {
    this.custodianBaseUrl = custodianBaseUrl;
  }

  public String getAgreementId() {
    return agreementId;
  }

  public void setAgreementId(final String agreementId) {
    this.agreementId = agreementId;
  }

  public String getFragmentChecksum() {
    return fragmentChecksum;
  }

  public void setFragmentChecksum(final String fragmentChecksum) {
    this.fragmentChecksum = fragmentChecksum;
  }

  public long getFragmentSizeBytes() {
    return fragmentSizeBytes;
  }

  public void setFragmentSizeBytes(final long fragmentSizeBytes) {
    this.fragmentSizeBytes = fragmentSizeBytes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }

  /** Composite primary key (fileId, fragmentId). */
  public static class PK implements Serializable {

    private String fileId;
    private String fragmentId;

    public PK() {}

    public PK(final String fileId, final String fragmentId) {
      this.fileId = fileId;
      this.fragmentId = fragmentId;
    }

    public String getFileId() {
      return fileId;
    }

    public void setFileId(final String fileId) {
      this.fileId = fileId;
    }

    public String getFragmentId() {
      return fragmentId;
    }

    public void setFragmentId(final String fragmentId) {
      this.fragmentId = fragmentId;
    }

    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof PK pk)) {
        return false;
      }
      return Objects.equals(fileId, pk.fileId) && Objects.equals(fragmentId, pk.fragmentId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fileId, fragmentId);
    }
  }
}
