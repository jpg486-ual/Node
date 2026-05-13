package es.ual.node.custodyliveness.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Tracking del último probe entrante recibido por el origen desde un custodian. Una fila por
 * custodian, el origen actualiza {@code lastInboundProbeAt} en cada probe recibido y resetea {@code
 * consecutiveFailures} a 0. El {@code OriginInverseProbeWorker} lee esta tabla para detectar
 * custodians silentes (now - lastInboundProbeAt > probe-interval + tolerance) y disparar probes
 * inversas.
 *
 * <p>Persistido en {@code origin_custodian_health}.
 *
 * @param custodianNodeId identificador del custodian
 * @param custodianBaseUrl URL base del custodian (cacheada para que el origin inverse worker no
 *     necesite cross-lookup en {@code client_fragment_placement})
 * @param lastInboundProbeAt timestamp del último probe entrante recibido. {@code null} antes del
 *     primer probe
 * @param consecutiveFailures intentos de probe inverso fallidos desde el último probe entrante OK
 * @param updatedAt timestamp del último cambio del registro
 */
public record OriginCustodianHealth(
    String custodianNodeId,
    String custodianBaseUrl,
    Instant lastInboundProbeAt,
    int consecutiveFailures,
    Instant updatedAt) {

  public OriginCustodianHealth {
    Objects.requireNonNull(custodianNodeId, "custodianNodeId");
    Objects.requireNonNull(custodianBaseUrl, "custodianBaseUrl");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (custodianNodeId.isBlank()) {
      throw new IllegalArgumentException("custodianNodeId must not be blank");
    }
    if (custodianBaseUrl.isBlank()) {
      throw new IllegalArgumentException("custodianBaseUrl must not be blank");
    }
    if (consecutiveFailures < 0) {
      throw new IllegalArgumentException("consecutiveFailures must not be negative");
    }
    custodianNodeId = custodianNodeId.trim();
    custodianBaseUrl = custodianBaseUrl.trim();
  }

  /**
   * Crea un registro inicial / reseteado tras probe entrante OK: {@code consecutiveFailures=0},
   * {@code lastInboundProbeAt=now}.
   */
  public static OriginCustodianHealth onInboundProbe(
      final String custodianNodeId, final String custodianBaseUrl, final Instant now) {
    return new OriginCustodianHealth(custodianNodeId, custodianBaseUrl, now, 0, now);
  }
}
