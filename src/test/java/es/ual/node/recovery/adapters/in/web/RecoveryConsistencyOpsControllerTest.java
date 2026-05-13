package es.ual.node.recovery.adapters.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import es.ual.node.recovery.application.RecoveryObservabilityService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Unit tests for {@link RecoveryConsistencyOpsController}. */
class RecoveryConsistencyOpsControllerTest {

  @Test
  void metricsReturnsExpectedMetricKeys() {
    final RecoveryObservabilityService observabilityService = new RecoveryObservabilityService();
    observabilityService.onCleanupRun();
    observabilityService.onReconciledMissingPayload(1);
    observabilityService.onStoreCompensated();

    final RecoveryConsistencyOpsController controller =
        new RecoveryConsistencyOpsController(observabilityService);

    final ResponseEntity<RecoveryConsistencyMetricsPayload> response = controller.metrics();

    assertEquals(200, response.getStatusCode().value());
    final Map<String, Number> metrics = response.getBody().metrics();
    assertTrue(metrics.containsKey("recovery.consistency.compensation.total"));
    assertTrue(metrics.containsKey("recovery.consistency.reconciliation.total"));
    assertTrue(metrics.containsKey("recovery.cleanup.run.total"));
    assertTrue(metrics.containsKey("recovery.cleanup.run.error.total"));
  }
}
