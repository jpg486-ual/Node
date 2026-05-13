package es.ual.node.persistence.jpa;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for negotiation agreements. */
public interface NegotiationAgreementJpaRepository
    extends JpaRepository<NegotiationAgreementJpaEntity, String> {

  /**
   * Counts pending agreements.
   *
   * @return pending count
   */
  int countByStatus(String status);

  /**
   * Expires pending agreements older than current instant.
   *
   * @param now current instant
   * @return updated rows
   */
  @Modifying
  @Query(
      "update NegotiationAgreementJpaEntity a set a.status = 'EXPIRED' where a.status = 'PENDING'"
          + " and a.expiresAt <= :now")
  int expirePending(@Param("now") Instant now);
}
