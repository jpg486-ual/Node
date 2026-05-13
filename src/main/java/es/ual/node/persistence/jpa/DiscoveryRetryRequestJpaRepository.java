package es.ual.node.persistence.jpa;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for discovery retry requests. */
public interface DiscoveryRetryRequestJpaRepository
    extends JpaRepository<DiscoveryRetryRequestJpaEntity, String> {

  /** Counts retry requests in one status. */
  long countByStatus(String status);

  /**
   * Returns all retry requests ordered by latest updates first.
   *
   * @return retry requests
   */
  List<DiscoveryRetryRequestJpaEntity> findAllByOrderByUpdatedAtDescCreatedAtDesc();

  /** Finds pending requests due for retry ordered by next attempt instant. */
  @Query(
      """
      select d from DiscoveryRetryRequestJpaEntity d
      where d.status = 'PENDING' and d.nextAttemptAt <= :now
      order by d.nextAttemptAt asc, d.createdAt asc
      """)
  List<DiscoveryRetryRequestJpaEntity> findDue(@Param("now") Instant now, Pageable pageable);

  /** Deletes terminal entries older than threshold. */
  @Modifying
  @Query(
      """
      delete from DiscoveryRetryRequestJpaEntity d
      where d.status in ('RESOLVED', 'FAILED') and d.updatedAt < :threshold
      """)
  int deleteTerminalOlderThan(@Param("threshold") Instant threshold);
}
