package es.ual.node.fragmentstorage.ports.out;

import es.ual.node.fragmentstorage.domain.CustodyFragment;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting fragment metadata under the <strong>custody</strong> domain
 * asymmetric exchange, peer holds fragment for sender). Disjoint from the recovery domain {@code
 * RecoveryOrphanFragmentPort}: separate physical table, separate adapter, separate lifecycle.
 *
 * <p>Writers: {@code FragmentCustodyService}, {@code TutorReturnCustodyEscalationPort} (deletes
 * during the custody → orphan transition).
 */
public interface CustodyFragmentPort {

  /** Saves custody fragment metadata. */
  void save(CustodyFragment fragment);

  /** Finds custody fragment metadata by fragment id. */
  Optional<CustodyFragment> findByFragmentId(String fragmentId);

  /** Finds custody fragments by requester (sender) node id. */
  List<CustodyFragment> findByRequesterNodeId(String requesterNodeId);

  /** Returns all custody fragments ordered by latest storage time first. */
  List<CustodyFragment> findAll();

  /**
   * Returns custody fragments whose TTL ({@code expiresAt}) is strictly before {@code threshold},
   * ordered by oldest expiry first (FIFO escalation), capped at {@code limit} entries.
   *
   * <p>Consumido exclusivamente por {@code CustodyExpiryEscalationWorker} para localizar candidatos
   * al flujo RETURN_TO_TUTOR.
   */
  List<CustodyFragment> findExpired(java.time.Instant threshold, int limit);

  /** Removes custody fragment by fragment id. */
  void deleteByFragmentId(String fragmentId);

  /**
   * Returns the sum of {@code sizeBytes} across all custody fragments currently held by this node.
   * Consumido por {@code LocalCapacityCheckAdapter} para el guardia "¿tengo disco para aceptar otro
   * fragment?". Fuente de verdad coherente con el modelo asimétrico actual, sustituye al contador
   * `capacity_counter` que solo contabilizaba reservas vía /negotiate.
   *
   * @return total bytes occupied by custody fragments; 0 when empty
   */
  long totalSizeBytes();
}
