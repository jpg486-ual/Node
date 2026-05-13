package es.ual.node.discovery.application;

import es.ual.node.discovery.domain.DiscoveryRetryStatus;
import es.ual.node.discovery.ports.out.DiscoveryCandidateDirectoryPort;
import es.ual.node.discovery.ports.out.DiscoveryRetryQueuePort;

/** Aggregates operational discovery metrics from durable ports. */
public class DiscoveryObservabilityService {

  private final DiscoveryRetryQueuePort retryQueuePort;
  private final DiscoveryCandidateDirectoryPort candidateDirectoryPort;

  /**
   * Creates observability service.
   *
   * @param retryQueuePort retry queue port
   * @param candidateDirectoryPort candidate directory port
   */
  public DiscoveryObservabilityService(
      final DiscoveryRetryQueuePort retryQueuePort,
      final DiscoveryCandidateDirectoryPort candidateDirectoryPort) {
    if (retryQueuePort == null || candidateDirectoryPort == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.retryQueuePort = retryQueuePort;
    this.candidateDirectoryPort = candidateDirectoryPort;
  }

  /**
   * Returns current discovery metrics snapshot.
   *
   * @return metrics snapshot
   */
  public DiscoveryMetricsSnapshot snapshot() {
    return new DiscoveryMetricsSnapshot(
        retryQueuePort.countByStatus(DiscoveryRetryStatus.PENDING),
        retryQueuePort.countByStatus(DiscoveryRetryStatus.RESOLVED),
        retryQueuePort.countByStatus(DiscoveryRetryStatus.FAILED),
        candidateDirectoryPort.countActiveCandidates());
  }
}
