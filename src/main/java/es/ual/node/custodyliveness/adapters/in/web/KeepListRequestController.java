package es.ual.node.custodyliveness.adapters.in.web;

import es.ual.node.custodyliveness.application.CustodyLivenessProperties;
import es.ual.node.custodyliveness.application.OriginInboundKeepListService;
import es.ual.node.identitysecurity.adapters.in.web.RequestSignatureValidator;
import es.ual.node.userregistration.adapters.in.web.ApiErrorPayload;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint POST /ops/custody-liveness/keep-list-request, el origen recibe el probe periódico
 * iniciado por el custodian.
 *
 * <p>El custodian envía la lista de fragments custodiados para este origen. El origen consulta sus
 * placements activos y devuelve la whitelist de fragments a conservar. Cualquier fragmento listado
 * en la request que NO aparezca en la respuesta debe ser purgado por el custodian (hard-delete +
 * cancel agreement).
 */
@RestController
@ConditionalOnProperty(prefix = "node.custody-liveness", name = "enabled", havingValue = "true")
@RequestMapping("/ops/custody-liveness")
public class KeepListRequestController {

  private static final Logger LOGGER = LoggerFactory.getLogger(KeepListRequestController.class);

  private final OriginInboundKeepListService keepListService;
  private final CustodyLivenessProperties livenessProperties;

  /** Creates controller. */
  public KeepListRequestController(
      final OriginInboundKeepListService keepListService,
      final CustodyLivenessProperties livenessProperties) {
    if (keepListService == null || livenessProperties == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    this.keepListService = keepListService;
    this.livenessProperties = livenessProperties;
  }

  /** Procesa el probe entrante del custodian y devuelve la whitelist. */
  @PostMapping("/keep-list-request")
  public ResponseEntity<?> keepListRequest(
      final HttpServletRequest request, @RequestBody final KeepListRequestPayload payload) {
    if (payload == null) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("CUSTODY_LIVENESS_INVALID_REQUEST", "payload required"));
    }
    final String custodianNodeId = request.getHeader(RequestSignatureValidator.HEADER_NODE_ID);
    if (custodianNodeId == null || custodianNodeId.isBlank()) {
      return ResponseEntity.badRequest()
          .body(
              ApiErrorPayload.of(
                  "CUSTODY_LIVENESS_INVALID_REQUEST",
                  "missing X-Node-Id header (validated by signature interceptor)"));
    }
    final String custodianBaseUrl = livenessProperties.getRemoteBaseUrls().get(custodianNodeId);
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      // El origen no tiene ruta al custodian. No puede actualizar origin_custodian_health
      // (custodian_base_url es NOT NULL). Rechazar, el operador debe configurar
      // node.custody-liveness.remote-base-urls.
      LOGGER
          .atWarn()
          .setMessage(
              "Inbound keep-list-request from unknown custodian (no remote-base-urls entry)")
          .addKeyValue("event", "CUSTODY_LIVENESS_UNKNOWN_CUSTODIAN")
          .addKeyValue("custodianNodeId", custodianNodeId)
          .log();
      return ResponseEntity.status(409)
          .body(
              ApiErrorPayload.of(
                  "CUSTODY_LIVENESS_UNKNOWN_CUSTODIAN",
                  "custodian baseUrl not configured at this origin"));
    }
    try {
      final var keep =
          keepListService.processProbe(
              custodianNodeId, custodianBaseUrl, payload.requesterNodeId(), payload.fragmentIds());
      return ResponseEntity.ok(new KeepListResponsePayload(keep));
    } catch (IllegalArgumentException exception) {
      return ResponseEntity.badRequest()
          .body(ApiErrorPayload.of("CUSTODY_LIVENESS_INVALID_REQUEST", exception.getMessage()));
    }
  }
}
