package es.ual.node.recovery.ports.out;

import es.ual.node.recovery.domain.CustodiedFileManifest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for proactive tutor custody of FileManifests. los métodos {@code deleteExpired} y
 * {@code extendByFileId} han desaparecido. El tutor mantiene la whitelist invertida: probe
 * periódico al supervisado pidiendo qué manifests conservar.
 */
public interface CustodiedFileManifestPort {

  /** Persists the manifest record. Replaces any existing entry by {@code fileId}. */
  void save(CustodiedFileManifest manifest);

  /** Looks up a manifest by file id. */
  Optional<CustodiedFileManifest> findByFileId(String fileId);

  /**
   * Lists manifests custodied for a given requester node, ordered by stored timestamp descending.
   */
  List<CustodiedFileManifest> findByRequesterNodeId(String requesterNodeId);

  /**
   * Hard-deletes the manifest associated with a given file id. Invoked from {@code
   * FileSystemService.delete} when the user removes an entry: the manifest at the tutor stops being
   * useful (no recovery possible without backing fragments) so it is purged immediately.
   *
   * @param fileId file id of the manifest to delete
   * @return {@code true} if a row was deleted, {@code false} if no manifest existed for this id
   */
  boolean deleteByFileId(String fileId);

  /**
   * Hard-deletes the manifests whose file ids appear in the supervised whitelist purge set. Used by
   * the {@code TutorManifestKeepListWorker}: tras recibir whitelist del supervisado, purga todos
   * los manifests del nodo supervisado que no aparezcan en la lista keep.
   *
   * @param fileIds set of file ids to delete
   * @return count of rows deleted
   */
  int deleteByFileIds(Iterable<String> fileIds);

  /**
   * Updates the {@code lastSupervisedCheckAt} timestamp and resets {@code
   * consecutiveOriginFailures=0} on all manifests of a supervised node. Invoked by {@code
   * TutorManifestKeepListWorker} on a successful probe response (origen respondió OK con
   * whitelist).
   *
   * @param requesterNodeId supervised node id
   * @param at timestamp of the successful probe
   * @return count of rows updated
   */
  int markSupervisedCheckOk(String requesterNodeId, Instant at);

  /**
   * Increments {@code consecutiveOriginFailures} on all manifests of a supervised node. Invoked by
   * {@code TutorManifestKeepListWorker} on a failed probe (origen no respondió). El tutor NO purga
   * cuando origen no responde, protección invertida.
   *
   * @param requesterNodeId supervised node id
   * @param at timestamp of the failed probe
   * @return count of rows updated
   */
  int markSupervisedCheckFailed(String requesterNodeId, Instant at);

  /**
   * Lists distinct supervised node ids that currently have manifests under custody. Used by {@code
   * TutorManifestKeepListWorker} to iterate supervised peers.
   */
  List<String> listSupervisedNodeIds();
}
