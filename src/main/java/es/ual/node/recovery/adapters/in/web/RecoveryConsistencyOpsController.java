package es.ual.node.recovery.adapters.in.web;

import es.ual.node.recovery.application.RecoveryObservabilityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ops endpoints for recovery consistency metrics. */
@RestController
@ConditionalOnProperty(prefix = "node.features", name = "recovery-enabled", havingValue = "true")
@RequestMapping("/ops/recovery/consistency")
public class RecoveryConsistencyOpsController {

  private final RecoveryObservabilityService recoveryObservabilityService;

  /** Creates controller. */
  public RecoveryConsistencyOpsController(
      final RecoveryObservabilityService recoveryObservabilityService) {
    if (recoveryObservabilityService == null) {
      throw new IllegalArgumentException("recoveryObservabilityService must not be null");
    }
    this.recoveryObservabilityService = recoveryObservabilityService;
  }

  /** Returns aggregated recovery consistency metrics. */
  @GetMapping("/metrics")
  public ResponseEntity<RecoveryConsistencyMetricsPayload> metrics() {
    return ResponseEntity.ok(
        RecoveryConsistencyMetricsPayload.fromSnapshot(recoveryObservabilityService.snapshot()));
  }
}
