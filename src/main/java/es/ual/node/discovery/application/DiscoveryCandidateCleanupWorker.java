package es.ual.node.discovery.application;

import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Supernode-side worker that prunes candidate rows whose {@code lastSeenAt} is older than {@code
 * staleness-seconds}. The freshness filter on {@code findActiveCandidates} already hides such rows
 * from query results, this worker just keeps the row count bounded so a cluster with high churn
 * does not accumulate zombie entries indefinitely.
 *
 * <p>Configurable cadence ({@code node.discovery.cleanup.interval-millis}, default 1h) and
 * staleness threshold ({@code node.discovery.cleanup.staleness-seconds}, default 900s = 3× the
 * default {@code freshness-seconds=300}, leaving margin for delayed renewals).
 */
public class DiscoveryCandidateCleanupWorker {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(DiscoveryCandidateCleanupWorker.class);

  private final DiscoveryCandidateDirectoryPort directoryPort;
  private final Clock clock;
  private final long stalenessSeconds;

  /**
   * Creates worker.
   *
   * @param directoryPort candidate directory port
   * @param clock clock for staleness threshold computation
   * @param stalenessSeconds rows older than this are pruned
   */
  public DiscoveryCandidateCleanupWorker(
      final DiscoveryCandidateDirectoryPort directoryPort,
      final Clock clock,
      final long stalenessSeconds) {
    if (directoryPort == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (stalenessSeconds <= 0) {
      throw new IllegalArgumentException("stalenessSeconds must be greater than zero");
    }
    this.directoryPort = directoryPort;
    this.clock = clock;
    this.stalenessSeconds = stalenessSeconds;
  }

  /** Prunes stale candidate rows. */
  @Scheduled(
      fixedDelayString = "${node.discovery.cleanup.interval-millis:3600000}",
      initialDelayString = "${node.discovery.cleanup.initial-delay-millis:60000}")
  public void cleanup() {
    final Instant staleBefore = clock.instant().minusSeconds(stalenessSeconds);
    try {
      final int deleted = directoryPort.deleteStale(staleBefore);
      LOGGER
          .atInfo()
          .setMessage("Discovery candidate cleanup completed")
          .addKeyValue("deletedCount", deleted)
          .addKeyValue("stalenessSeconds", stalenessSeconds)
          .log();
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .setMessage("Discovery candidate cleanup failed")
          .addKeyValue("error", exception.getMessage())
          .log();
    }
  }
}
