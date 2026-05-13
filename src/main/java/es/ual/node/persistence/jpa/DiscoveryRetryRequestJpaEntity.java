package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for durable discovery retry requests. */
@Entity
@Table(name = "discovery_retry_request")
public class DiscoveryRetryRequestJpaEntity {

  @Id
  @Column(name = "id", nullable = false, length = 128)
  private String id;

  @Column(name = "node_id", nullable = false, length = 128)
  private String nodeId;

  @Column(name = "failure_domain", nullable = false, length = 255)
  private String failureDomain;

  @Column(name = "requested_bucket", nullable = false)
  private long requestedBucket;

  @Column(name = "ratio", nullable = false)
  private double ratio;

  @Column(name = "max_candidates", nullable = false)
  private int maxCandidates;

  @Column(name = "target_failure_domain", length = 255)
  private String targetFailureDomain;

  @Column(name = "distribution_plan_json", nullable = false, length = 2000)
  private String distributionPlanJson;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "resolved_candidate_count")
  private Integer resolvedCandidateCount;

  @Column(name = "last_error", length = 512)
  private String lastError;

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

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

  public long getRequestedBucket() {
    return requestedBucket;
  }

  public void setRequestedBucket(final long requestedBucket) {
    this.requestedBucket = requestedBucket;
  }

  public double getRatio() {
    return ratio;
  }

  public void setRatio(final double ratio) {
    this.ratio = ratio;
  }

  public int getMaxCandidates() {
    return maxCandidates;
  }

  public void setMaxCandidates(final int maxCandidates) {
    this.maxCandidates = maxCandidates;
  }

  public String getTargetFailureDomain() {
    return targetFailureDomain;
  }

  public void setTargetFailureDomain(final String targetFailureDomain) {
    this.targetFailureDomain = targetFailureDomain;
  }

  public String getDistributionPlanJson() {
    return distributionPlanJson;
  }

  public void setDistributionPlanJson(final String distributionPlanJson) {
    this.distributionPlanJson = distributionPlanJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(final String status) {
    this.status = status;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(final int attemptCount) {
    this.attemptCount = attemptCount;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public void setNextAttemptAt(final Instant nextAttemptAt) {
    this.nextAttemptAt = nextAttemptAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public void setResolvedAt(final Instant resolvedAt) {
    this.resolvedAt = resolvedAt;
  }

  public Integer getResolvedCandidateCount() {
    return resolvedCandidateCount;
  }

  public void setResolvedCandidateCount(final Integer resolvedCandidateCount) {
    this.resolvedCandidateCount = resolvedCandidateCount;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(final String lastError) {
    this.lastError = lastError;
  }
}
