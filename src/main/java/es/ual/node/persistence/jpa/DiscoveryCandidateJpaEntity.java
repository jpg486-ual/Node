package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for durable discovery candidate directory. */
@Entity
@Table(name = "discovery_candidate")
public class DiscoveryCandidateJpaEntity {

  @Id
  @Column(name = "node_id", nullable = false, length = 128)
  private String nodeId;

  @Column(name = "failure_domain", nullable = false, length = 255)
  private String failureDomain;

  @Column(name = "base_url", nullable = false, length = 512)
  private String baseUrl;

  @Column(name = "original_requested_bucket", nullable = false)
  private long originalRequestedBucket;

  @Column(name = "accepted_buckets_json", nullable = false, length = 2000)
  private String acceptedBucketsJson;

  @Column(name = "healthy", nullable = false)
  private boolean healthy;

  @Column(name = "available_bytes", nullable = false)
  private long availableBytes;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public String getNodeId() {
    return nodeId;
  }

  public void setNodeId(final String nodeId) {
    this.nodeId = nodeId;
  }

  public String getFailureDomain() {
    return failureDomain;
  }

  public void setFailureDomain(final String failureDomain) {
    this.failureDomain = failureDomain;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(final String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public long getOriginalRequestedBucket() {
    return originalRequestedBucket;
  }

  public void setOriginalRequestedBucket(final long originalRequestedBucket) {
    this.originalRequestedBucket = originalRequestedBucket;
  }

  public String getAcceptedBucketsJson() {
    return acceptedBucketsJson;
  }

  public void setAcceptedBucketsJson(final String acceptedBucketsJson) {
    this.acceptedBucketsJson = acceptedBucketsJson;
  }

  public boolean isHealthy() {
    return healthy;
  }

  public void setHealthy(final boolean healthy) {
    this.healthy = healthy;
  }

  public long getAvailableBytes() {
    return availableBytes;
  }

  public void setAvailableBytes(final long availableBytes) {
    this.availableBytes = availableBytes;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(final Instant lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
