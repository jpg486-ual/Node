package es.ual.node.discovery.application;

import java.util.Map;

/** Point-in-time snapshot for discovery operational metrics. */
public record DiscoveryMetricsSnapshot(
    long pendingQueueCount,
    long resolvedQueueCount,
    long failedQueueCount,
    long activeCandidatesCount) {

  /**
   * Returns named metrics map for ops payloads.
   *
   * @return immutable metrics map
   */
  public Map<String, Number> asNamedMetrics() {
    final long totalQueueCount = pendingQueueCount + resolvedQueueCount + failedQueueCount;
    return Map.ofEntries(
        Map.entry("discovery.queue.pending.count", pendingQueueCount),
        Map.entry("discovery.queue.resolved.count", resolvedQueueCount),
        Map.entry("discovery.queue.failed.count", failedQueueCount),
        Map.entry("discovery.queue.total.count", totalQueueCount),
        Map.entry("discovery.candidates.active.count", activeCandidatesCount));
  }
}
