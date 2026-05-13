package es.ual.node.discovery.application;

import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Manages durable queue of discovery requests pending candidate availability. */
public class DiscoveryRetryQueueService {

  private final DiscoveryRetryQueuePort queuePort;
  private final DiscoveryRetryProperties properties;
  private final Clock clock;

  /** Creates service. */
  public DiscoveryRetryQueueService(
      final DiscoveryRetryQueuePort queuePort,
      final DiscoveryRetryProperties properties,
      final Clock clock) {
    if (queuePort == null || properties == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.queuePort = queuePort;
    this.properties = properties;
    this.clock = clock;
  }

  /** Creates a durable pending retry request. */
  public DiscoveryRetryRequest enqueue(final DiscoveryRequest request) {
    final Instant now = clock.instant();
    final DiscoveryRetryRequest queued =
        new DiscoveryRetryRequest(
            UUID.randomUUID().toString(),
            request,
            DiscoveryRetryStatus.PENDING,
            0,
            now,
            now,
            now,
            null,
            null,
            null);
    return queuePort.save(queued);
  }

  /** Gets retry request by id. */
  public DiscoveryRetryRequest get(final String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    return queuePort
        .findById(id.trim())
        .orElseThrow(() -> new NoSuchElementException("retry request not found"));
  }

  /**
   * Cancels one queued request explicitly from operations API.
   *
   * @param id request id
   * @param reason cancellation reason
   * @return updated retry request
   */
  public DiscoveryRetryRequest cancel(final String id, final String reason) {
    final DiscoveryRetryRequest current = get(id);
    if (current.status() == DiscoveryRetryStatus.RESOLVED
        || current.status() == DiscoveryRetryStatus.FAILED) {
      return current;
    }

    final Instant now = clock.instant();
    final String effectiveReason =
        normalizeError(reason) == null ? "Cancelled by operator" : normalizeError(reason);
    return queuePort.save(
        new DiscoveryRetryRequest(
            current.id(),
            current.request(),
            DiscoveryRetryStatus.FAILED,
            current.attemptCount(),
            now,
            current.createdAt(),
            now,
            now,
            null,
            effectiveReason));
  }

  /**
   * Forces an immediate retry attempt on a queued request.
   *
   * <p>Transitions {@code FAILED → PENDING} (resetting {@code resolvedAt}) or leaves a {@code
   * PENDING} entry in {@code PENDING} while setting {@code nextAttemptAt = now}. {@code RESOLVED}
   * entries are rejected: they represent a successful outcome and must not be replayed.
   *
   * @param id request id
   * @param reason operator-supplied reason (nullable; default "Requeued by operator")
   * @return updated retry request
   */
  public DiscoveryRetryRequest requeueNow(final String id, final String reason) {
    final DiscoveryRetryRequest current = get(id);
    if (current.status() == DiscoveryRetryStatus.RESOLVED) {
      throw new IllegalStateException("retry is already resolved");
    }
    final Instant now = clock.instant();
    final String normalizedReason =
        normalizeError(reason) == null ? "Requeued by operator" : normalizeError(reason);
    return queuePort.save(
        new DiscoveryRetryRequest(
            current.id(),
            current.request(),
            DiscoveryRetryStatus.PENDING,
            current.attemptCount(),
            now,
            current.createdAt(),
            now,
            null,
            null,
            normalizedReason));
  }

  /** Finds due requests for retry. */
  public List<DiscoveryRetryRequest> findDue() {
    return queuePort.findDue(clock.instant(), Math.max(1, properties.getBatchSize()));
  }

  /** Returns all persisted retry requests for read-only operational inspection. */
  public List<DiscoveryRetryRequest> findAll() {
    return queuePort.findAll();
  }

  /** Marks request as resolved. */
  public void markResolved(final DiscoveryRetryRequest current, final int resolvedCandidateCount) {
    final Instant now = clock.instant();
    queuePort.save(
        new DiscoveryRetryRequest(
            current.id(),
            current.request(),
            DiscoveryRetryStatus.RESOLVED,
            current.attemptCount() + 1,
            now,
            current.createdAt(),
            now,
            now,
            resolvedCandidateCount,
            null));
  }

  /** Marks request for next retry attempt or failed when attempts exceeded. */
  public void markRetry(final DiscoveryRetryRequest current, final String lastError) {
    final int nextAttempt = current.attemptCount() + 1;
    final Instant now = clock.instant();
    final boolean exhausted =
        properties.getMaxAttempts() > 0 && nextAttempt >= properties.getMaxAttempts();
    final DiscoveryRetryStatus nextStatus =
        exhausted ? DiscoveryRetryStatus.FAILED : DiscoveryRetryStatus.PENDING;
    final long delaySeconds = exhausted ? 0 : calculateDelaySeconds(nextAttempt);
    final Instant nextAttemptAt = exhausted ? now : now.plusSeconds(delaySeconds);
    queuePort.save(
        new DiscoveryRetryRequest(
            current.id(),
            current.request(),
            nextStatus,
            nextAttempt,
            nextAttemptAt,
            current.createdAt(),
            now,
            exhausted ? now : null,
            null,
            normalizeError(lastError)));
  }

  /** Deletes old terminal records. */
  public void cleanupTerminal() {
    if (properties.getTerminalRetentionSeconds() <= 0) {
      return;
    }
    queuePort.deleteTerminalOlderThan(
        clock.instant().minusSeconds(properties.getTerminalRetentionSeconds()));
  }

  /** Returns whether automatic retry worker is enabled. */
  public boolean isRetryEnabled() {
    return properties.isEnabled();
  }

  private long calculateDelaySeconds(final int attemptNumber) {
    final long base = Math.max(1, properties.getBaseDelaySeconds());
    final long max = Math.max(base, properties.getMaxDelaySeconds());
    final int exponent = Math.max(0, attemptNumber - 1);
    final double scaled = base * Math.pow(2d, exponent);
    return Math.min(max, (long) scaled);
  }

  private String normalizeError(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    final String trimmed = value.trim();
    return trimmed.length() > 512 ? trimmed.substring(0, 512) : trimmed;
  }
}
