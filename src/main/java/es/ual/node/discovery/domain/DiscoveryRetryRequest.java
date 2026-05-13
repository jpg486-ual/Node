package es.ual.node.discovery.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted retry request for discovery operations that initially found no candidates. */
public record DiscoveryRetryRequest(
    String id,
    DiscoveryRequest request,
    DiscoveryRetryStatus status,
    int attemptCount,
    Instant nextAttemptAt,
    Instant createdAt,
    Instant updatedAt,
    Instant resolvedAt,
    Integer resolvedCandidateCount,
    String lastError) {

  /** Creates a validated retry request. */
  public DiscoveryRetryRequest {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (status == null) {
      throw new IllegalArgumentException("status must not be null");
    }
    if (attemptCount < 0) {
      throw new IllegalArgumentException("attemptCount must be zero or greater");
    }
    if (nextAttemptAt == null || createdAt == null || updatedAt == null) {
      throw new IllegalArgumentException("timestamps must not be null");
    }
    if (status == DiscoveryRetryStatus.RESOLVED && resolvedAt == null) {
      throw new IllegalArgumentException("resolvedAt is required when status is RESOLVED");
    }
    if (status == DiscoveryRetryStatus.RESOLVED && resolvedCandidateCount == null) {
      throw new IllegalArgumentException(
          "resolvedCandidateCount is required when status is RESOLVED");
    }
  }

  /** Creates a request object from persisted fields. */
  public static DiscoveryRequest toDiscoveryRequest(
      final String nodeId,
      final String failureDomain,
      final long requestedBucket,
      final double ratio,
      final int maxCandidates,
      final String targetFailureDomain,
      final Map<String, Integer> distributionPlan) {
    final Map<String, Integer> safePlan =
        distributionPlan == null ? Map.of() : new LinkedHashMap<>(distributionPlan);
    return new DiscoveryRequest(
        nodeId,
        failureDomain,
        requestedBucket,
        ratio,
        maxCandidates,
        targetFailureDomain,
        safePlan);
  }
}
