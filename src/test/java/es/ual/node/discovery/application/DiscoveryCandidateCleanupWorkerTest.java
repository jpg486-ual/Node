package es.ual.node.discovery.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import es.ual.node.discovery.adapters.out.memory.InMemoryDiscoveryCandidateDirectoryAdapter;
import es.ual.node.discovery.domain.DiscoveryCandidateProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DiscoveryCandidateCleanupWorker}. */
class DiscoveryCandidateCleanupWorkerTest {

  @Test
  void rejectsNullDependencies() {
    final InMemoryDiscoveryCandidateDirectoryAdapter port =
        new InMemoryDiscoveryCandidateDirectoryAdapter();
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryCandidateCleanupWorker(null, Clock.systemUTC(), 900L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryCandidateCleanupWorker(port, null, 900L));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryCandidateCleanupWorker(port, Clock.systemUTC(), 0L));
  }

  @Test
  void cleanupPrunesOnlyStaleCandidates() {
    final AtomicReference<Instant> currentTime =
        new AtomicReference<>(Instant.parse("2026-05-03T10:00:00Z"));
    final Clock movableClock =
        new Clock() {
          @Override
          public Instant instant() {
            return currentTime.get();
          }

          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(final java.time.ZoneId zone) {
            return this;
          }
        };

    final InMemoryDiscoveryCandidateDirectoryAdapter port =
        new InMemoryDiscoveryCandidateDirectoryAdapter(movableClock);
    port.upsertCandidate(
        new DiscoveryCandidateProfile(
            "stale-node", "zone-a/rack-1", "http://stale:8080", 1024L, Set.of()));
    // Advance the clock so the next upsert stamps a much newer lastSeenAt.
    currentTime.set(currentTime.get().plus(Duration.ofMinutes(20)));
    port.upsertCandidate(
        new DiscoveryCandidateProfile(
            "fresh-node", "zone-b/rack-1", "http://fresh:8080", 1024L, Set.of()));

    // staleness=900s (15min). At t+20min from the stale upsert, it is older than the threshold.
    final DiscoveryCandidateCleanupWorker worker =
        new DiscoveryCandidateCleanupWorker(port, movableClock, 900L);
    worker.cleanup();

    assertEquals(1, port.findActiveCandidates().size());
    assertEquals("fresh-node", port.findActiveCandidates().getFirst().nodeId());
  }

  @Test
  void cleanupSwallowsPortExceptionsSoSchedulerStaysAlive() {
    final DiscoveryCandidateCleanupWorker worker =
        new DiscoveryCandidateCleanupWorker(new ThrowingPort(), Clock.systemUTC(), 900L);

    assertDoesNotThrow(worker::cleanup);
  }

  private static final class ThrowingPort
      implements es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort {
    @Override
    public java.util.List<DiscoveryCandidateProfile> findActiveCandidates() {
      return java.util.List.of();
    }

    @Override
    public long countActiveCandidates() {
      return 0;
    }

    @Override
    public void upsertCandidate(final DiscoveryCandidateProfile profile) {}

    @Override
    public void removeCandidate(final String nodeId) {}

    @Override
    public int deleteStale(final Instant staleBefore) {
      throw new IllegalStateException("simulated db failure");
    }
  }
}
