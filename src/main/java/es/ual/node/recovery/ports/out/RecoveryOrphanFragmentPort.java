package es.ual.node.recovery.ports.out;

import es.ual.node.recovery.domain.RecoveryOrphanFragment;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting fragment metadata under the <strong>recovery</strong> domain. Orphan
 * fragments returned to the tutor by peers via {@code RETURN_TO_TUTOR} escalation when the
 * requester is detected unresponsive. Disjoint from the custody domain {@code CustodyFragmentPort}:
 * separate physical table {@code recovery_orphan_fragment}, separate adapter, separate lifecycle.
 *
 * <ol>
 *   <li>Origen recovered: {@code POST /recovery/orphan-fragments/{fragmentId}/claim} → bytes →
 *       {@code POST /recovery/orphan-fragments/{fragmentId}/ack} → tutor borra internamente.
 * </ol>
 *
 * <p>Writers: {@code TutorRecoveryService} (called from {@code RecoveryController} POST endpoint
 * receiving orphans).
 */
public interface RecoveryOrphanFragmentPort {

  /** Saves orphan fragment metadata. */
  void save(RecoveryOrphanFragment fragment);

  /** Finds orphan fragment metadata by fragment id. */
  Optional<RecoveryOrphanFragment> findByFragmentId(String fragmentId);

  /** Finds orphan fragments by requester node id. */
  List<RecoveryOrphanFragment> findByRequesterNodeId(String requesterNodeId);

  /** Returns all orphan fragments ordered by latest storage time first. */
  List<RecoveryOrphanFragment> findAll();

  /** Returns orphan fragment ids up to a maximum batch size. */
  List<String> findAllFragmentIds(int limit);

  /** Removes orphan fragment by fragment id. */
  void deleteByFragmentId(String fragmentId);
}
