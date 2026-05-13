package es.ual.node.custodyliveness.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.custodyliveness.application.CustodyLivenessMetricsSnapshot;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CustodyLivenessMetricsPayload}. */
class CustodyLivenessMetricsPayloadTest {

  @Test
  void payloadExposesNamedMetricsFromSnapshot() {
    final CustodyLivenessMetricsSnapshot snapshot =
        new CustodyLivenessMetricsSnapshot(1L, 2L, 0L, 1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L);

    final CustodyLivenessMetricsPayload payload =
        CustodyLivenessMetricsPayload.fromSnapshot(snapshot);

    assertTrue(payload.metrics().containsKey("custody.liveness.inbound.total"));
    assertTrue(payload.metrics().containsKey("custody.liveness.outbound.scheduled.total"));
    assertTrue(payload.metrics().containsKey("custody.liveness.transition.suspect.total"));
    assertEquals(1L, payload.metrics().get("custody.liveness.inbound.total"));
    assertTrue(payload.signals().isEmpty(), "signals quedan vacíos");
  }
}
