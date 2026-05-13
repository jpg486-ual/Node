package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.application.CustodyLivenessService;
import es.ual.node.custodyliveness.domain.CustodyProbeResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for inter-node custody liveness probes. */
@RestController
@ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
@RequestMapping("/custody/liveness/probes")
public class CustodyLivenessController {

  private final CustodyLivenessService service;

  /** Creates controller. */
  public CustodyLivenessController(final CustodyLivenessService service) {
    if (service == null) {
      throw new IllegalArgumentException("service must not be null");
    }
    this.service = service;
  }

  /** Handles inbound custody liveness probes. */
  @PostMapping
  public ResponseEntity<CustodyProbeResponsePayload> probe(
      @RequestBody final CustodyProbeRequestPayload payload) {
    try {
      final CustodyProbeResponse response = service.handleInboundProbe(payload.toDomain());
      return ResponseEntity.ok(CustodyProbeResponsePayload.fromDomain(response));
    } catch (IllegalArgumentException exception) {
      return ResponseEntity.badRequest().build();
    }
  }
}
