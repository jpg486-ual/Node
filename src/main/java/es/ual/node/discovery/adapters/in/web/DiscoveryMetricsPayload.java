package es.ual.node.discovery.adapters.in.web;

import es.ual.node.discovery.application.DiscoveryMetricsSnapshot;
import java.util.Map;

/** Ops payload exposing discovery metrics. */
public record DiscoveryMetricsPayload(Map<String, Number> metrics) {

  /**
   * Maps metrics snapshot to payload.
   *
   * @param snapshot metrics snapshot
   * @return payload
   */
  public static DiscoveryMetricsPayload fromSnapshot(final DiscoveryMetricsSnapshot snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot must not be null");
    }
    return new DiscoveryMetricsPayload(snapshot.asNamedMetrics());
  }
}
