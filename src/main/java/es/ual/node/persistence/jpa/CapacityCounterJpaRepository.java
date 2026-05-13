package es.ual.node.persistence.jpa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for aggregate capacity usage counter. */
public interface CapacityCounterJpaRepository
    extends JpaRepository<CapacityCounterJpaEntity, Integer> {

  /** Loads counter row with pessimistic lock to serialize reserve/release operations. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select c from CapacityCounterJpaEntity c
      where c.id = :id
      """)
  Optional<CapacityCounterJpaEntity> findByIdForUpdate(@Param("id") Integer id);
}
