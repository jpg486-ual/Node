package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.application.CustodyLivenessMetricsSnapshot;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import java.util.Map;

/** Ops payload exposing custody liveness metrics. */
public record CustodyLivenessMetricsPayload(
    Map<String, Number> metrics, Map<String, String> signals) {

  /**
   * Maps metrics snapshot to payload.
   *
   * @param snapshot metrics snapshot
   * @return payload
   */
  public static CustodyLivenessMetricsPayload fromSnapshot(
      final CustodyLivenessMetricsSnapshot snapshot) {
    return fromSnapshot(snapshot, null);
  }

  /**
   * Maps metrics snapshot to payload.
   *
   * @param snapshot metrics snapshot
   * @param properties custody liveness properties (currently unused, legacy signature)
   * @return payload
   */
  public static CustodyLivenessMetricsPayload fromSnapshot(
      final CustodyLivenessMetricsSnapshot snapshot, final CustodyLivenessProperties properties) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot must not be null");
    }
    return new CustodyLivenessMetricsPayload(Map.copyOf(snapshot.asNamedMetrics()), Map.of());
  }
}
