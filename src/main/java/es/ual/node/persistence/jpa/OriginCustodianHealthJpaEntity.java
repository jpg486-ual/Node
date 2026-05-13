package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA entity para el tracking de probes entrantes en el origen. Persiste en {@code
 * origin_custodian_health}. Una fila por custodian.
 */
@Entity
@Table(name = "origin_custodian_health")
public class OriginCustodianHealthJpaEntity {

  @Id
  @Column(name = "custodian_node_id", nullable = false, length = 128)
  private String custodianNodeId;

  @Column(name = "custodian_base_url", nullable = false, length = 512)
  private String custodianBaseUrl;

  @Column(name = "last_inbound_probe_at")
  private Instant lastInboundProbeAt;

  @Column(name = "consecutive_failures", nullable = false)
  private int consecutiveFailures;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

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

  public Instant getLastInboundProbeAt() {
    return lastInboundProbeAt;
  }

  public void setLastInboundProbeAt(final Instant lastInboundProbeAt) {
    this.lastInboundProbeAt = lastInboundProbeAt;
  }

  public int getConsecutiveFailures() {
    return consecutiveFailures;
  }

  public void setConsecutiveFailures(final int consecutiveFailures) {
    this.consecutiveFailures = consecutiveFailures;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
