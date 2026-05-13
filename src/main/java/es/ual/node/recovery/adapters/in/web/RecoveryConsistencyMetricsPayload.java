package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.application.RecoveryMetricsSnapshot;
import java.util.Map;

/** Ops payload exposing recovery consistency metrics. */
public record RecoveryConsistencyMetricsPayload(Map<String, Number> metrics) {

  /**
   * Maps metrics snapshot to payload.
   *
   * @param snapshot metrics snapshot
   * @return payload
   */
  public static RecoveryConsistencyMetricsPayload fromSnapshot(
      final RecoveryMetricsSnapshot snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot must not be null");
    }
    return new RecoveryConsistencyMetricsPayload(snapshot.asNamedMetrics());
  }
}
