package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.application.CustodyLivenessService;
import es.ual.node.custodyliveness.domain.CustodyProbeSession;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ops endpoints for custody liveness session inspection. */
@RestController
@ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
@RequestMapping("/ops/custody-liveness")
public class CustodyLivenessOpsController {

  private final CustodyLivenessService service;
  private final CustodyLivenessProperties properties;

  /** Creates controller. */
  public CustodyLivenessOpsController(
      final CustodyLivenessService service, final CustodyLivenessProperties properties) {
    if (service == null || properties == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.service = service;
    this.properties = properties;
  }

  /** Returns a single session by id. */
  @GetMapping("/sessions/{id}")
  public ResponseEntity<CustodyProbeSessionPayload> session(
      @PathVariable("id") final String sessionId) {
    try {
      final CustodyProbeSession session = service.findSession(sessionId);
      return ResponseEntity.ok(CustodyProbeSessionPayload.fromDomain(session));
    } catch (NoSuchElementException exception) {
      return ResponseEntity.notFound().build();
    }
  }

  /** Returns sessions filtered by remote node id. */
  @GetMapping("/remote/{nodeId}")
  public ResponseEntity<List<CustodyProbeSessionPayload>> byRemote(
      @PathVariable("nodeId") final String nodeId) {
    final List<CustodyProbeSessionPayload> payload =
        service.findByRemoteNodeId(nodeId).stream()
            .map(CustodyProbeSessionPayload::fromDomain)
            .toList();
    return ResponseEntity.ok(payload);
  }

  /** Returns aggregated custody liveness metrics. */
  @GetMapping("/metrics")
  public ResponseEntity<CustodyLivenessMetricsPayload> metrics() {
    return ResponseEntity.ok(
        CustodyLivenessMetricsPayload.fromSnapshot(service.metricsSnapshot(), properties));
  }

  /** Enqueues an immediate outbound probe for remote node. */
  @PostMapping("/remote/{nodeId}/probe-now")
  public ResponseEntity<CustodyProbeSessionPayload> probeNow(
      @PathVariable("nodeId") final String nodeId) {
    try {
      final CustodyProbeSession session = service.scheduleProbeNow(nodeId);
      return ResponseEntity.ok(CustodyProbeSessionPayload.fromDomain(session));
    } catch (IllegalArgumentException exception) {
      return ResponseEntity.badRequest().build();
    }
  }
}
