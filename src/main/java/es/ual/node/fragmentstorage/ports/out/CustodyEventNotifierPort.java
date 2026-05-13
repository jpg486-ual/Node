package es.ual.node.fragmentstorage.ports.out;

/**
 * Outbound notification port invoked after a custody fragment is successfully stored. Allows
 * interested modules (e.g. {@code custody-liveness}) to react, typically by scheduling an outbound
 * probe session against the requester (origin) so that origin failure is detected and fragments
 * escalate to the requester's tutor without operator intervention.
 *
 * <p>Implementations must be idempotent: a single requester may produce many notifications in quick
 * succession (one per fragment of one upload) and the side effect must coalesce.
 */
public interface CustodyEventNotifierPort {

  /**
   * Called after a custody fragment was persisted successfully (metadata + payload). Failures must
   * not propagate, the custody store has already succeeded and the notification is best-effort.
   *
   * @param requesterNodeId origin's node identifier (the {@code senderNodeId} of the store request)
   */
  void onCustodyFragmentStored(String requesterNodeId);
}
