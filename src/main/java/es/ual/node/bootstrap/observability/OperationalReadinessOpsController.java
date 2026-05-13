package es.ual.node.bootstrap.observability;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ops endpoints exposing runtime readiness and controlled degradation state. */
@RestController
@RequestMapping("/ops/system")
public class OperationalReadinessOpsController {

  private final OperationalReadinessService operationalReadinessService;

  /** Creates controller. */
  public OperationalReadinessOpsController(
      final OperationalReadinessService operationalReadinessService) {
    if (operationalReadinessService == null) {
      throw new IllegalArgumentException("operationalReadinessService must not be null");
    }
    this.operationalReadinessService = operationalReadinessService;
  }

  /** Returns hardened readiness signal for operations and automation. */
  @GetMapping("/readiness")
  public ResponseEntity<OperationalReadinessPayload> readiness() {
    return toResponse(operationalReadinessService.snapshot());
  }

  /** Returns health signal aligned with readiness classification. */
  @GetMapping("/health")
  public ResponseEntity<OperationalReadinessPayload> health() {
    return toResponse(operationalReadinessService.snapshot());
  }

  private ResponseEntity<OperationalReadinessPayload> toResponse(
      final OperationalReadinessPayload payload) {
    if (OperationalReadinessPayload.STATUS_NOT_READY.equals(payload.status())) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(payload);
    }
    return ResponseEntity.ok(payload);
  }
}
