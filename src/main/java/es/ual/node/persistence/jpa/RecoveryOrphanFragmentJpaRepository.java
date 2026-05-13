package es.ual.node.persistence.jpa;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for recovery-domain orphan fragments. */
public interface RecoveryOrphanFragmentJpaRepository
    extends JpaRepository<RecoveryOrphanFragmentJpaEntity, String> {

  List<RecoveryOrphanFragmentJpaEntity> findAllByOrderByStoredAtDesc();

  List<RecoveryOrphanFragmentJpaEntity> findByRequesterNodeId(String requesterNodeId);

  @Query("select r.fragmentId from RecoveryOrphanFragmentJpaEntity r order by r.storedAt desc")
  List<String> findAllFragmentIds(Pageable pageable);
}
