package es.ual.node.persistence.jpa;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@link RecoveryFileManifestJpaEntity}. */
public interface RecoveryFileManifestJpaRepository
    extends JpaRepository<RecoveryFileManifestJpaEntity, String> {

  List<RecoveryFileManifestJpaEntity> findByRequesterNodeIdOrderByStoredAtDesc(
      String requesterNodeId);

  @Query("SELECT DISTINCT m.requesterNodeId FROM RecoveryFileManifestJpaEntity m")
  List<String> findDistinctRequesterNodeIds();

  @Modifying
  @Query(
      "UPDATE RecoveryFileManifestJpaEntity m SET m.lastSupervisedCheckAt = :at, "
          + "m.consecutiveOriginFailures = 0 WHERE m.requesterNodeId = :requesterNodeId")
  int markSupervisedCheckOk(
      @Param("requesterNodeId") String requesterNodeId, @Param("at") Instant at);

  @Modifying
  @Query(
      "UPDATE RecoveryFileManifestJpaEntity m SET m.lastSupervisedCheckAt = :at, "
          + "m.consecutiveOriginFailures = m.consecutiveOriginFailures + 1 "
          + "WHERE m.requesterNodeId = :requesterNodeId")
  int markSupervisedCheckFailed(
      @Param("requesterNodeId") String requesterNodeId, @Param("at") Instant at);

  @Modifying
  @Query("DELETE FROM RecoveryFileManifestJpaEntity m WHERE m.fileId IN :fileIds")
  int deleteByFileIds(@Param("fileIds") Iterable<String> fileIds);
}
