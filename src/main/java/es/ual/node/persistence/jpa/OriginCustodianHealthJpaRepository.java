package es.ual.node.persistence.jpa;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository para origin_custodian_health. */
public interface OriginCustodianHealthJpaRepository
    extends JpaRepository<OriginCustodianHealthJpaEntity, String> {

  /**
   * Devuelve los custodians silentes ordenados {@code last_inbound_probe_at IS NULL} o anterior a
   * threshold. Usado por OriginInverseProbeWorker.
   */
  @Query(
      "SELECT h FROM OriginCustodianHealthJpaEntity h "
          + "WHERE h.lastInboundProbeAt IS NULL OR h.lastInboundProbeAt < :threshold "
          + "ORDER BY h.custodianNodeId")
  List<OriginCustodianHealthJpaEntity> findSilent(@Param("threshold") Instant threshold);
}
