package es.ual.node.negotiation.adapters.in.web;

import es.ual.node.negotiation.application.NegotiationService;
import es.ual.node.negotiation.domain.NegotiationAgreement;
import es.ual.node.negotiation.domain.NegotiationCreateRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter exposing negotiation lifecycle endpoints.
 *
 * <p>Doblemente gatado: requiere {@code node.features.negotiation-enabled=true} (gate genérico del
 * módulo) <em>y</em> {@code node.features.negotiation-formal-enabled=true} (gate específico de la
 * superficie formal `/negotiate/**`, default {@code false}). El flujo cliente productivo en cluster
 * cerrado no la usa — se enciende solo donde se quiera ejercitar admission control formal
 * explícito.
 */
@RestController
@ConditionalOnProperty(
    prefix = "node.features",
    name = "negotiation-formal-enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequestMapping("/negotiate")
public class NegotiationController {

  private final NegotiationService negotiationService;

  /**
   * Creates controller.
   *
   * @param negotiationService negotiation service
   */
  public NegotiationController(final NegotiationService negotiationService) {
    if (negotiationService == null) {
      throw new IllegalArgumentException("negotiationService must not be null");
    }
    this.negotiationService = negotiationService;
  }

  /**
   * Creates a negotiation agreement.
   *
   * @param payload create payload
   * @return created agreement payload
   */
  @PostMapping
  public ResponseEntity<NegotiationAgreementPayload> create(
      @RequestBody final NegotiationCreatePayload payload) {
    final NegotiationCreateRequest request = payload.toDomain();
    final NegotiationAgreement agreement = negotiationService.createAgreement(request);
    return ResponseEntity.ok(NegotiationAgreementPayload.fromDomain(agreement));
  }

  /**
   * Confirms a pending agreement.
   *
   * @param agreementId agreement id
   * @param payload confirmation payload
   * @return confirmed agreement payload
   */
  @PostMapping("/{id}/confirm")
  public ResponseEntity<NegotiationAgreementPayload> confirm(
      @PathVariable("id") final String agreementId,
      @RequestBody final NegotiationConfirmPayload payload) {
    final NegotiationAgreement agreement =
        negotiationService.confirmAgreement(agreementId, payload.targetSignature());
    return ResponseEntity.ok(NegotiationAgreementPayload.fromDomain(agreement));
  }

  /**
   * Cancels a pending agreement.
   *
   * @param agreementId agreement id
   * @return cancelled agreement payload
   */
  @PostMapping("/{id}/cancel")
  public ResponseEntity<NegotiationAgreementPayload> cancel(
      @PathVariable("id") final String agreementId) {
    final NegotiationAgreement agreement = negotiationService.cancelAgreement(agreementId);
    return ResponseEntity.ok(NegotiationAgreementPayload.fromDomain(agreement));
  }

  /**
   * Returns agreement state.
   *
   * @param agreementId agreement id
   * @return agreement payload
   */
  @GetMapping("/{id}")
  public ResponseEntity<NegotiationAgreementPayload> get(
      @PathVariable("id") final String agreementId) {
    final NegotiationAgreement agreement = negotiationService.getAgreement(agreementId);
    return ResponseEntity.ok(NegotiationAgreementPayload.fromDomain(agreement));
  }
}
