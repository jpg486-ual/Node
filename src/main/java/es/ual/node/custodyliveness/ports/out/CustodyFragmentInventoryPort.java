package es.ual.node.custodyliveness.ports.out;

import es.ual.node.custodyliveness.domain.CustodyProbeFragment;
import java.time.Instant;
import java.util.List;

/** Outbound port for listing locally custodied fragments per remote requester node. */
public interface CustodyFragmentInventoryPort {

  /**
   * Returns fragments currently custodied for a remote requester node.
   *
   * @param requesterNodeId requester node id
   * @param now evaluation timestamp
   * @return fragment descriptors eligible for liveness probe payload
   */
  List<CustodyProbeFragment> findCustodiedForRequester(String requesterNodeId, Instant now);

  /**
   * Devuelve la lista distinta de {@code requester_node_id} para los que este custodian tiene
   * fragments almacenados. Usado por el {@code CustodianProbeWorker} para iterar por origen al
   * iniciar probes periódicos. Default vacío para legacy adapters.
   *
   * @return lista distinta de requester ids
   */
  default List<String> listDistinctRequesterNodeIds() {
    return List.of();
  }

  /**
   * Devuelve fragments cuyo TTL ha expirado y que NO han sido renovados por probe activo (cluster
   * outage / origen permanently unreachable). Consumido exclusivamente por {@code
   * CustodyExpiryEscalationWorker} para localizar candidatos al flujo RETURN_TO_TUTOR.
   *
   * <p>El record retornado incluye el {@code requesterNodeId} para permitir agrupación previa al
   * dispatch de escalation. Cap por {@code limit} para batch-processing.
   *
   * @param threshold corte temporal — fragments con {@code expiresAt < threshold}
   * @param limit máximo número de fragments a retornar
   * @return descriptors de fragments expirados, agrupables por requester
   */
  default List<ExpiredCustodyEntry> findExpiredCustodied(
      final java.time.Instant threshold, final int limit) {
    return List.of();
  }

  /** Descriptor mínimo de un fragment custodiado expirado para el flujo escalation. */
  record ExpiredCustodyEntry(
      String fragmentId,
      String agreementId,
      String requesterNodeId,
      String checksum,
      int sizeBytes,
      java.time.Instant expiresAt) {

    /** Convierte a {@link CustodyProbeFragment} para reusar el path applyEscalation. */
    public CustodyProbeFragment toProbeFragment() {
      return new CustodyProbeFragment(fragmentId, agreementId, checksum, sizeBytes);
    }
  }
}
