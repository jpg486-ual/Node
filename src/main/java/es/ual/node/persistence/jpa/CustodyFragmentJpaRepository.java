package es.ual.node.persistence.jpa;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for custody-domain fragments.
 *
 * <p>La eliminación pasa por el worker {@code CustodyExpiryEscalationWorker} que invoca el flujo
 * RETURN_TO_TUTOR (con fallback de mantención + warning). Solo {@code findExpiredAt} sobrevive como
 * query read-only para que el worker localice candidatos.
 */
public interface CustodyFragmentJpaRepository
    extends JpaRepository<CustodyFragmentJpaEntity, String> {

  List<CustodyFragmentJpaEntity> findAllByOrderByStoredAtDesc();

  List<CustodyFragmentJpaEntity> findByRequesterNodeId(String requesterNodeId);

  /**
   * Returns custody fragments whose {@code expiresAt} is strictly before the threshold, ordered by
   * oldest expiry first to favour FIFO escalation. Limited via {@link Pageable} so the worker can
   * batch-process.
   */
  List<CustodyFragmentJpaEntity> findByExpiresAtBeforeOrderByExpiresAtAsc(
      Instant threshold, Pageable pageable);

  /**
   * Returns the sum of {@code size_bytes} across all custody fragments. Consumido por el guardia
   * "¿tengo disco para aceptar este fragment?". {@code COALESCE} para devolver 0 cuando la tabla
   * está vacía en lugar de null.
   */
  @Query("select coalesce(sum(c.sizeBytes), 0) from CustodyFragmentJpaEntity c")
  long sumSizeBytes();
}
