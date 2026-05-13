package es.ual.node.discovery.application;

import es.ual.node.discovery.domain.DiscoveryResponse;
import es.ual.node.discovery.domain.DiscoveryRetryRequest;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Scheduled processor for pending discovery retry requests. */
public class DiscoveryRetryWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryRetryWorker.class);

  private final DiscoveryService discoveryService;
  private final DiscoveryRetryQueueService retryQueueService;
  private final ObservationRegistry observationRegistry;

  /** Creates worker (legacy). */
  public DiscoveryRetryWorker(
      final DiscoveryService discoveryService, final DiscoveryRetryQueueService retryQueueService) {
    this(discoveryService, retryQueueService, ObservationRegistry.NOOP);
  }

  /** Creates worker with observation registry for domain spans. */
  public DiscoveryRetryWorker(
      final DiscoveryService discoveryService,
      final DiscoveryRetryQueueService retryQueueService,
      final ObservationRegistry observationRegistry) {
    if (discoveryService == null || retryQueueService == null || observationRegistry == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.discoveryService = discoveryService;
    this.retryQueueService = retryQueueService;
    this.observationRegistry = observationRegistry;
  }

  /** Processes due retry requests and retries candidate discovery. */
  @Scheduled(fixedDelayString = "${node.discovery.retry.fixed-delay-millis:5000}")
  public void processDue() {
    Observation.createNotStarted("node.discovery.retry.cycle", observationRegistry)
        .observe(this::doProcessDue);
  }

  private void doProcessDue() {
    if (!retryQueueService.isRetryEnabled()) {
      return;
    }

    final List<DiscoveryRetryRequest> due = retryQueueService.findDue();
    int processed = 0;
    int failed = 0;
    for (DiscoveryRetryRequest request : due) {
      try {
        final DiscoveryResponse response = discoveryService.discover(request.request());
        if (response.candidates().isEmpty()) {
          retryQueueService.markRetry(request, "No candidates available yet");
          failed++;
          continue;
        }
        retryQueueService.markResolved(request, response.candidates().size());
        processed++;
        LOGGER
            .atInfo()
            .setMessage("Discovery retry resolved")
            .addKeyValue("retryId", request.id())
            .addKeyValue("requesterNodeId", request.request().nodeId())
            .addKeyValue("candidates", response.candidates().size())
            .log();
      } catch (Exception exception) {
        retryQueueService.markRetry(request, exception.getMessage());
        failed++;
      }
    }

    LOGGER
        .atDebug()
        .setMessage("Discovery retry cycle completed")
        .addKeyValue("entries.processed", processed)
        .addKeyValue("entries.failed", failed)
        .log();

    retryQueueService.cleanupTerminal();
  }
}
