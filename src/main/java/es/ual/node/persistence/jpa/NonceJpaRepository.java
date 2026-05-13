package es.ual.node.persistence.jpa;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for used nonces. */
public interface NonceJpaRepository extends JpaRepository<NonceJpaEntity, String> {

  /**
   * Removes expired nonces.
   *
   * @param now current instant
   * @return deleted rows
   */
  @Modifying
  @Query("delete from NonceJpaEntity n where n.expiresAt <= :now")
  int deleteExpired(@Param("now") Instant now);
}
