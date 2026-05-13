package es.ual.node.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for client-side fragment placement records. */
public interface ClientFragmentPlacementJpaRepository
    extends JpaRepository<ClientFragmentPlacementJpaEntity, ClientFragmentPlacementJpaEntity.PK> {

  /**
   * Returns all placements of a file ordered by fragmentIndex.
   *
   * @param fileId file identifier
   * @return placements ordered by index
   */
  @Query(
      "SELECT p FROM ClientFragmentPlacementJpaEntity p "
          + "WHERE p.fileId = :fileId ORDER BY p.blockIndex, p.fragmentIndex")
  List<ClientFragmentPlacementJpaEntity> findAllByFileIdOrderByIndex(
      @Param("fileId") String fileId);

  /**
   * Removes all placements of a file.
   *
   * @param fileId file identifier
   * @return number of rows deleted
   */
  @Modifying
  @Query("DELETE FROM ClientFragmentPlacementJpaEntity p WHERE p.fileId = :fileId")
  int deleteAllByFileId(@Param("fileId") String fileId);

  /**
   * Returns every placement persisted at this origin. Used by the recovery worker to enumerate the
   * catalog after restore).
   *
   * @return all placements ordered deterministically by composite key
   */
  @Query(
      "SELECT p FROM ClientFragmentPlacementJpaEntity p "
          + "ORDER BY p.fileId, p.blockIndex, p.fragmentIndex")
  List<ClientFragmentPlacementJpaEntity> findAllOrderByKey();

  /**
   * Removes a single placement by composite key (Used to unhook the old peer mapping after
   * redistribute).
   *
   * @param fileId file identifier
   * @param fragmentId fragment identifier
   * @return number of rows deleted (0 or 1)
   */
  @Modifying
  @Query(
      "DELETE FROM ClientFragmentPlacementJpaEntity p "
          + "WHERE p.fileId = :fileId AND p.fragmentId = :fragmentId")
  int deleteByFileIdAndFragmentId(
      @Param("fileId") String fileId, @Param("fragmentId") String fragmentId);

  /**
   * Returns all placements held by a given custodian (Origin inverse probe worker enumerates a
   * silent custodian's placements to trigger inventory check).
   *
   * @param custodianNodeId custodian node identifier
   * @return placements ordered by file/block/fragment for deterministic processing
   */
  @Query(
      "SELECT p FROM ClientFragmentPlacementJpaEntity p "
          + "WHERE p.custodianNodeId = :custodianNodeId "
          + "ORDER BY p.fileId, p.blockIndex, p.fragmentIndex")
  List<ClientFragmentPlacementJpaEntity> findAllByCustodianNodeId(
      @Param("custodianNodeId") String custodianNodeId);

  /**
   * Returns all placements whose {@code custodianBaseUrl} matches the given URL. Used by {@code
   * OriginInboundKeepListService.processProbe} to look up placements unambiguously when the probe
   * arrives signed by the cryptographic node id (the legacy {@code
   * custodian_node_id="peer@<baseUrl>"} sentinel does not match).
   *
   * @param custodianBaseUrl custodian base URL (e.g. {@code http://node2:8080})
   * @return placements ordered by file/block/fragment for deterministic processing
   */
  @Query(
      "SELECT p FROM ClientFragmentPlacementJpaEntity p "
          + "WHERE p.custodianBaseUrl = :custodianBaseUrl "
          + "ORDER BY p.fileId, p.blockIndex, p.fragmentIndex")
  List<ClientFragmentPlacementJpaEntity> findAllByCustodianBaseUrl(
      @Param("custodianBaseUrl") String custodianBaseUrl);

  /**
   * Looks up a placement by {@code fragmentId} alone. Used by the custody-liveness {@code
   * AgreementBackedCustodyFragmentInterestPort} fallback when the agreement is synthetic ({@code
   * client-upload-<UUID>}) and no row exists in {@code negotiation_agreement}: the placement IS the
   * source of truth for "this fragment still belongs to a live FsEntry on this origin".
   *
   * <p>Returns at most one row because we enforce unique fragmentIds globally.
   *
   * @param fragmentId fragment identifier
   * @return placement if any, empty otherwise
   */
  @Query("SELECT p FROM ClientFragmentPlacementJpaEntity p WHERE p.fragmentId = :fragmentId")
  Optional<ClientFragmentPlacementJpaEntity> findByFragmentId(
      @Param("fragmentId") String fragmentId);

  /**
   * Atomic update of health-state columns only. Avoids race with concurrent placement updates by
   * touching exclusively {@code health_status}, {@code last_check_at} y {@code
   * consecutive_failures}.
   *
   * @return number of rows updated (0 if placement absent, race con DELETE)
   */
  @Modifying
  @Query(
      "UPDATE ClientFragmentPlacementJpaEntity p SET "
          + "p.healthStatus = :healthStatus, "
          + "p.lastCheckAt = :lastCheckAt, "
          + "p.consecutiveFailures = :consecutiveFailures "
          + "WHERE p.fileId = :fileId AND p.fragmentId = :fragmentId")
  int updateHealthByFileIdAndFragmentId(
      @Param("fileId") String fileId,
      @Param("fragmentId") String fragmentId,
      @Param("healthStatus") String healthStatus,
      @Param("lastCheckAt") java.time.Instant lastCheckAt,
      @Param("consecutiveFailures") int consecutiveFailures);
}
