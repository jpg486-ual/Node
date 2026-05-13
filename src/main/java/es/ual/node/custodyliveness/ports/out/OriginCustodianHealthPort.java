package es.ual.node.custodyliveness.ports.out;

import es.ual.node.custodyliveness.domain.OriginCustodianHealth;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary del tracking de probes entrantes en el origen.
 *
 * <p>Persistido en {@code origin_custodian_health}. El handler del endpoint {@code POST
 * /ops/custody-liveness/keep-list-request} llama a {@link #upsertOnInboundProbe} en cada probe
 * entrante. El {@code OriginInverseProbeWorker} llama a {@link #findSilentCustodians} para enumerar
 * candidatos a probe inverso.
 */
public interface OriginCustodianHealthPort {

  /**
   * Reset / inicializa el tracking del custodian tras recibir probe entrante exitoso. Equivale a
   * UPSERT con {@code lastInboundProbeAt=now, consecutiveFailures=0}.
   *
   * @param custodianNodeId identificador del custodian
   * @param custodianBaseUrl URL base del custodian (cacheada para inverse probe)
   * @param now timestamp del probe entrante
   */
  void upsertOnInboundProbe(String custodianNodeId, String custodianBaseUrl, Instant now);

  /**
   * Devuelve el registro del custodian, si existe.
   *
   * @param custodianNodeId identificador del custodian
   * @return registro encapsulado en Optional
   */
  Optional<OriginCustodianHealth> findById(String custodianNodeId);

  /**
   * Devuelve los custodians silentes — aquellos cuyo {@code lastInboundProbeAt} es anterior a
   * {@code threshold}. NULL last es candidato (custodian que nunca ha pingueado pero está en
   * placements).
   *
   * @param threshold timestamp de corte
   * @return lista
   */
  List<OriginCustodianHealth> findSilentCustodians(Instant threshold);

  /**
   * Persiste el resultado del último probe inverso (success o failure incrementado).
   *
   * @param record registro actualizado
   */
  void save(OriginCustodianHealth record);
}
