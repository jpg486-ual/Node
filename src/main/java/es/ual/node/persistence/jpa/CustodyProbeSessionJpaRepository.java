package es.ual.node.persistence.jpa;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for custody probe sessions. */
public interface CustodyProbeSessionJpaRepository
    extends JpaRepository<CustodyProbeSessionJpaEntity, String> {

  /**
   * Returns all sessions ordered by last update descending.
   *
   * @return sessions
   */
  List<CustodyProbeSessionJpaEntity> findAllByOrderByUpdatedAtDescCreatedAtDesc();

  /**
   * Finds sessions by remote node ordered by last update descending.
   *
   * @param remoteNodeId remote node id
   * @return sessions
   */
  List<CustodyProbeSessionJpaEntity> findByRemoteNodeIdOrderByUpdatedAtDesc(String remoteNodeId);

  /**
   * Finds due sessions for outbound direction ordered by next attempt timestamp.
   *
   * @param direction direction value
   * @param now current instant
   * @param pageable page limit
   * @return due sessions
   */
  @Query(
      """
      select c
      from CustodyProbeSessionJpaEntity c
      where c.direction = :direction
        and c.nextAttemptAt is not null
        and c.nextAttemptAt <= :now
      order by c.nextAttemptAt asc
      """)
  List<CustodyProbeSessionJpaEntity> findDueByDirection(
      @Param("direction") String direction, @Param("now") Instant now, Pageable pageable);
}
