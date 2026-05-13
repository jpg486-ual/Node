package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryRetryQueuePort;
import es.ual.node.discovery.domain.DiscoveryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiscoveryRetryQueueService}. */
class DiscoveryRetryQueueServiceTest {

  @Test
  void enqueueCreatesPendingRequest() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRequest request = request();

    final DiscoveryRetryRequest queued = service.enqueue(request);

    assertNotNull(queued.id());
    assertEquals(DiscoveryRetryStatus.PENDING, queued.status());
    assertEquals(0, queued.attemptCount());
    assertEquals("requester", queued.request().nodeId());
  }

  @Test
  void markRetryAppliesBackoffAndTracksError() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRetryRequest queued = service.enqueue(request());

    service.markRetry(queued, "No candidates yet");
    final DiscoveryRetryRequest updated = service.get(queued.id());

    assertEquals(DiscoveryRetryStatus.PENDING, updated.status());
    assertEquals(1, updated.attemptCount());
    assertEquals("No candidates yet", updated.lastError());
    assertTrue(updated.nextAttemptAt().isAfter(updated.createdAt()));
  }

  @Test
  void markResolvedStoresCandidateCount() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRetryRequest queued = service.enqueue(request());

    service.markResolved(queued, 3);
    final DiscoveryRetryRequest resolved = service.get(queued.id());

    assertEquals(DiscoveryRetryStatus.RESOLVED, resolved.status());
    assertEquals(1, resolved.attemptCount());
    assertEquals(3, resolved.resolvedCandidateCount());
    assertNotNull(resolved.resolvedAt());
  }

  @Test
  void maxAttemptsMovesRequestToFailed() {
    final DiscoveryRetryQueueService service = createService(2);
    final DiscoveryRetryRequest queued = service.enqueue(request());

    service.markRetry(queued, "first failure");
    final DiscoveryRetryRequest first = service.get(queued.id());
    service.markRetry(first, "second failure");
    final DiscoveryRetryRequest failed = service.get(queued.id());

    assertEquals(DiscoveryRetryStatus.FAILED, failed.status());
    assertEquals(2, failed.attemptCount());
    assertNotNull(failed.resolvedAt());
  }

  @Test
  void cancelMovesPendingRequestToFailedWithReason() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRetryRequest queued = service.enqueue(request());

    final DiscoveryRetryRequest cancelled = service.cancel(queued.id(), "ops cancellation");

    assertEquals(DiscoveryRetryStatus.FAILED, cancelled.status());
    assertEquals("ops cancellation", cancelled.lastError());
    assertNotNull(cancelled.resolvedAt());
  }

  @Test
  void cancelUsesDefaultReasonWhenNotProvided() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRetryRequest queued = service.enqueue(request());

    final DiscoveryRetryRequest cancelled = service.cancel(queued.id(), null);

    assertEquals(DiscoveryRetryStatus.FAILED, cancelled.status());
    assertEquals("Cancelled by operator", cancelled.lastError());
  }

  @Test
  void requeueNowMovesFailedBackToPendingWithNowAsNextAttempt() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRetryRequest queued = service.enqueue(request());
    final DiscoveryRetryRequest failed = service.cancel(queued.id(), "initial failure");

    final DiscoveryRetryRequest requeued = service.requeueNow(failed.id(), "root cause fixed");

    assertEquals(DiscoveryRetryStatus.PENDING, requeued.status());
    assertEquals(
        failed.attemptCount(),
        requeued.attemptCount(),
        "attemptCount must be preserved for auditability");
    assertEquals(Instant.parse("2026-03-07T18:00:00Z"), requeued.nextAttemptAt());
    assertEquals("root cause fixed", requeued.lastError());
    org.junit.jupiter.api.Assertions.assertNull(requeued.resolvedAt());
    org.junit.jupiter.api.Assertions.assertNull(requeued.resolvedCandidateCount());
  }

  @Test
  void requeueNowOnPendingResetsNextAttemptAtToNow() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRetryRequest queued = service.enqueue(request());
    service.markRetry(queued, "no candidates yet");
    final DiscoveryRetryRequest pendingWithBackoff = service.get(queued.id());
    assertTrue(
        pendingWithBackoff.nextAttemptAt().isAfter(pendingWithBackoff.createdAt()),
        "precondition: backoff must have scheduled a future attempt");

    final DiscoveryRetryRequest requeued = service.requeueNow(pendingWithBackoff.id(), "force now");

    assertEquals(DiscoveryRetryStatus.PENDING, requeued.status());
    assertEquals(Instant.parse("2026-03-07T18:00:00Z"), requeued.nextAttemptAt());
    assertEquals(pendingWithBackoff.attemptCount(), requeued.attemptCount());
  }

  @Test
  void requeueNowOnResolvedThrows() {
    final DiscoveryRetryQueueService service = createService(0);
    final DiscoveryRetryRequest queued = service.enqueue(request());
    service.markResolved(queued, 3);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> service.requeueNow(queued.id(), "won't work"));
  }

  private DiscoveryRetryQueueService createService(final int maxAttempts) {
    final DiscoveryRetryProperties properties = new DiscoveryRetryProperties();
    properties.setMaxAttempts(maxAttempts);
    properties.setBaseDelaySeconds(5);
    properties.setMaxDelaySeconds(60);
    properties.setBatchSize(20);
    final Clock clock = Clock.fixed(Instant.parse("2026-03-07T18:00:00Z"), ZoneOffset.UTC);
    return new DiscoveryRetryQueueService(new InMemoryDiscoveryRetryQueuePort(), properties, clock);
  }

  private DiscoveryRequest request() {
    return new DiscoveryRequest("requester", "zone-a", 1024L, 1.1d, 10);
  }
}
