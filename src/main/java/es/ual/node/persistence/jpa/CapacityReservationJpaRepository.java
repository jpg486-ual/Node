package es.ual.node.persistence.jpa;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for durable capacity reservation ledger. */
public interface CapacityReservationJpaRepository
    extends JpaRepository<CapacityReservationJpaEntity, String> {

  /** Loads reservation row with pessimistic lock. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select r from CapacityReservationJpaEntity r
      where r.reservationKey = :reservationKey
      """)
  Optional<CapacityReservationJpaEntity> findByReservationKeyForUpdate(
      @Param("reservationKey") String reservationKey);
}
