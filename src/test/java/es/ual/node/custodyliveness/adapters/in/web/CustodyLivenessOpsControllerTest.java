package es.ual.node.custodyliveness.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import es.ual.node.custodyliveness.application.CustodyLivenessMetricsSnapshot;
import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.application.CustodyLivenessService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link CustodyLivenessOpsController}. */
@ExtendWith(MockitoExtension.class)
class CustodyLivenessOpsControllerTest {

  @Mock private CustodyLivenessService service;

  private CustodyLivenessProperties properties;
  private CustodyLivenessOpsController controller;

  @BeforeEach
  void setUp() {
    properties = new CustodyLivenessProperties();
    controller = new CustodyLivenessOpsController(service, properties);
  }

  @Test
  void metricsReturnsExpectedMetricKeys() {
    when(service.metricsSnapshot())
        .thenReturn(
            new CustodyLivenessMetricsSnapshot(10L, 12L, 3L, 8L, 4L, 7L, 5L, 2L, 1L, 0L, 0L));

    final ResponseEntity<CustodyLivenessMetricsPayload> response = controller.metrics();

    assertEquals(200, response.getStatusCode().value());
    final Map<String, Number> metrics = response.getBody().metrics();
    assertTrue(metrics.containsKey("custody.liveness.inbound.total"));
    assertTrue(metrics.containsKey("custody.liveness.outbound.scheduled.total"));
    assertTrue(metrics.containsKey("custody.liveness.outbound.deduplicated.total"));
    assertTrue(metrics.containsKey("custody.liveness.outbound.success.total"));
    assertTrue(metrics.containsKey("custody.liveness.outbound.failure.total"));
    assertTrue(metrics.containsKey("custody.liveness.transition.active.total"));
    assertTrue(metrics.containsKey("custody.liveness.transition.suspect.total"));
    assertTrue(metrics.containsKey("custody.liveness.transition.unresponsive.total"));
    assertTrue(metrics.containsKey("custody.liveness.transition.escalated.total"));
    assertTrue(metrics.containsKey("custody.liveness.escalation.deferred.total"));
  }
}
