package es.ual.node.persistence.jpa;

import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for durable discovery candidates. */
public interface DiscoveryCandidateJpaRepository
    extends JpaRepository<DiscoveryCandidateJpaEntity, String> {

  /**
   * Counts candidates that are currently active according to health, freshness and available
   * capacity.
   */
  @Query(
      """
      select count(d) from DiscoveryCandidateJpaEntity d
      where d.healthy = true
        and d.availableBytes >= :minimumAvailableBytes
        and d.lastSeenAt >= :freshnessThreshold
      """)
  long countActive(
      @Param("freshnessThreshold") Instant freshnessThreshold,
      @Param("minimumAvailableBytes") long minimumAvailableBytes);

  /**
   * Finds candidates that are currently active according to health, freshness and available
   * capacity.
   */
  @Query(
      """
      select d from DiscoveryCandidateJpaEntity d
      where d.healthy = true
        and d.availableBytes >= :minimumAvailableBytes
        and d.lastSeenAt >= :freshnessThreshold
      order by d.updatedAt desc, d.nodeId asc
      """)
  List<DiscoveryCandidateJpaEntity> findActive(
      @Param("freshnessThreshold") Instant freshnessThreshold,
      @Param("minimumAvailableBytes") long minimumAvailableBytes);

  /**
   * Deletes candidates whose {@code lastSeenAt} is strictly before the given threshold. Used by
   * {@link es.ual.node.discovery.application.DiscoveryCandidateCleanupWorker}.
   */
  @Modifying
  @Transactional
  @Query(
      """
      delete from DiscoveryCandidateJpaEntity d
      where d.lastSeenAt < :staleBefore
      """)
  int deleteByLastSeenAtBefore(@Param("staleBefore") Instant staleBefore);
}
