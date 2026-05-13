package es.ual.node.filesystem.ports.out;

import es.ual.node.filesystem.domain.FragmentPlacement;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for fragment placement records at the origin node.
 *
 * <p>One placement per fragment of a manifest. Used at download time to know which custodian holds
 * each fragment.
 */
public interface FragmentPlacementPort {

  /**
   * Persists one placement.
   *
   * @param placement placement to persist
   */
  void save(FragmentPlacement placement);

  /**
   * Returns all placements of a file ordered by fragmentIndex.
   *
   * @param fileId file identifier
   * @return list of placements (empty when no placements exist)
   */
  List<FragmentPlacement> findByFileId(String fileId);

  /**
   * Removes all placements of a file (called when the FsEntry is deleted permanently).
   *
   * @param fileId file identifier
   */
  void deleteByFileId(String fileId);

  /**
   * Returns every placement persisted at this origin. Used by the recovery worker after restore to
   * enumerate the catalog and verify peer inventory. Default implementation throws to keep legacy
   * adapters compiling without behaviour change; production adapters override.
   *
   * @return list of all placements
   */
  default List<FragmentPlacement> findAll() {
    throw new UnsupportedOperationException(
        "findAll() not implemented by this FragmentPlacementPort adapter");
  }

  /**
   * Removes a single placement by composite key (used after redistribute to unhook the old peer
   * mapping before inserting the fresh one).
   *
   * @param fileId backing file identifier
   * @param fragmentId fragment identifier within the file
   */
  default void deleteByFileIdAndFragmentId(String fileId, String fragmentId) {
    throw new UnsupportedOperationException(
        "deleteByFileIdAndFragmentId() not implemented by this FragmentPlacementPort adapter");
  }

  /**
   * Looks up a single placement by {@code fragmentId} alone. Used by the custody-liveness {@code
   * AgreementBackedCustodyFragmentInterestPort} fallback when the agreement is synthetic ({@code
   * client-upload-<UUID>} from direct upload) and there is no row in {@code negotiation_agreement}
   * to query: the placement IS the source of truth for "this fragment still belongs to a live
   * FsEntry on this origin". Default impl iterates {@code findAll()} for in-memory adapters;
   * production overrides with an indexed query.
   *
   * @param fragmentId fragment identifier
   * @return placement if any, empty otherwise
   */
  default Optional<FragmentPlacement> findByFragmentId(final String fragmentId) {
    if (fragmentId == null || fragmentId.isBlank()) {
      return Optional.empty();
    }
    final String trimmed = fragmentId.trim();
    return findAll().stream().filter(p -> trimmed.equals(p.fragmentId())).findFirst();
  }

  /**
   * Returns all placements held by a given custodian. Used by the {@code OriginInverseProbeWorker}
   * to enumerate placements of a silent custodian and trigger the inverse probe. Default impl
   * returns empty (legacy compat).
   *
   * @param custodianNodeId custodian node identifier
   * @return placements held by that custodian (empty if none)
   */
  default List<FragmentPlacement> findByCustodianNodeId(String custodianNodeId) {
    return List.of();
  }

  /**
   * Returns all placements whose {@code custodianBaseUrl} matches the given URL. Required for
   * {@code OriginInboundKeepListService.processProbe} because direct upload writes {@code
   * custodianNodeId="peer@<baseUrl>"} (legacy sentinel) while the keep-list probe arrives signed
   * with the cryptographic node id; looking up by {@code custodianBaseUrl} (carried in the probe
   * envelope) is the unambiguous way to find the placements without an additional
   * cryptographic→baseUrl mapping.
   *
   * <p>Default impl iterates {@code findAll()}; production overrides with a baseUrl-indexed query.
   *
   * @param custodianBaseUrl custodian base URL (e.g. {@code http://node2:8080})
   * @return placements held by the custodian at that baseUrl (empty if none)
   */
  default List<FragmentPlacement> findByCustodianBaseUrl(final String custodianBaseUrl) {
    if (custodianBaseUrl == null || custodianBaseUrl.isBlank()) {
      return List.of();
    }
    final String trimmed = custodianBaseUrl.trim();
    return findAll().stream().filter(p -> trimmed.equals(p.custodianBaseUrl())).toList();
  }

  /**
   * Updates only the health-state columns ({@code health_status}, {@code last_check_at}, {@code
   * consecutive_failures}) of a placement. Atomic, idempotente. No-op si el placement no existe
   * (race con DELETE concurrente). Default delega a {@code findByFileId+save} para tests minimal,
   * production adapters override con UPDATE atómico.
   *
   * @param updated placement con los nuevos valores de health (resto de campos deben matchear la
   *     row existente)
   */
  default void updateHealth(FragmentPlacement updated) {
    if (updated == null) {
      throw new IllegalArgumentException("updated must not be null");
    }
    deleteByFileIdAndFragmentId(updated.fileId(), updated.fragmentId());
    save(updated);
  }
}
