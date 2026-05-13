package es.ual.node.discovery.adapters.in.web;

import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import java.time.Instant;

/** Payload exposing persisted discovery retry status. */
public record DiscoveryRetryStatusPayload(
    String id,
    DiscoveryRetryStatus status,
    int attemptCount,
    Instant nextAttemptAt,
    Instant createdAt,
    Instant updatedAt,
    Instant resolvedAt,
    Integer resolvedCandidateCount,
    String lastError) {

  /** Maps domain retry request to payload. */
  public static DiscoveryRetryStatusPayload fromDomain(final DiscoveryRetryRequest request) {
    return new DiscoveryRetryStatusPayload(
        request.id(),
        request.status(),
        request.attemptCount(),
        request.nextAttemptAt(),
        request.createdAt(),
        request.updatedAt(),
        request.resolvedAt(),
        request.resolvedCandidateCount(),
        request.lastError());
  }
}
