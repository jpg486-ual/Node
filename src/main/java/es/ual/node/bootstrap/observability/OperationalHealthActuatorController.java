package es.ual.node.bootstrap.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight actuator-compatible health endpoints backed by operational readiness checks. */
@RestController
@RequestMapping("/actuator/health")
public class OperationalHealthActuatorController {

  private final OperationalReadinessService operationalReadinessService;

  /** Creates controller. */
  public OperationalHealthActuatorController(
      final OperationalReadinessService operationalReadinessService) {
    if (operationalReadinessService == null) {
      throw new IllegalArgumentException("operationalReadinessService must not be null");
    }
    this.operationalReadinessService = operationalReadinessService;
  }

  /** Returns health summary. */
  @GetMapping
  public ResponseEntity<Map<String, Object>> health() {
    return toResponse(operationalReadinessService.snapshot());
  }

  /** Returns explicit readiness summary. */
  @GetMapping("/readiness")
  public ResponseEntity<Map<String, Object>> readiness() {
    return toResponse(operationalReadinessService.snapshot());
  }

  private ResponseEntity<Map<String, Object>> toResponse(
      final OperationalReadinessPayload payload) {
    final String actuatorStatus = toActuatorStatus(payload.status());

    final Map<String, Object> details = new LinkedHashMap<>();
    details.put("readinessStatus", payload.status());
    details.put("acceptingTraffic", payload.acceptingTraffic());
    details.put("degraded", payload.degraded());
    details.put("checkedAt", payload.checkedAt());
    details.put("dependencies", payload.dependencies());

    final Map<String, Object> readinessComponent =
        Map.of("status", actuatorStatus, "details", Map.copyOf(details));

    final Map<String, Object> body =
        Map.of("status", actuatorStatus, "components", Map.of("readiness", readinessComponent));

    if (OperationalReadinessPayload.STATUS_NOT_READY.equals(payload.status())) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
    return ResponseEntity.ok(body);
  }

  private String toActuatorStatus(final String readinessStatus) {
    return OperationalReadinessPayload.STATUS_NOT_READY.equals(readinessStatus) ? "DOWN" : "UP";
  }
}
