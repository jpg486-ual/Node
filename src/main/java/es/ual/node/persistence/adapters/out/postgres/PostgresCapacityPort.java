package es.ual.node.persistence.adapters.out.postgres;

import es.ual.node.negotiation.ports.out.CapacityPort;
import es.ual.node.persistence.jpa.CapacityCounterJpaEntity;
import es.ual.node.persistence.jpa.CapacityCounterJpaRepository;
import es.ual.node.persistence.jpa.CapacityReservationJpaEntity;
import es.ual.node.persistence.jpa.CapacityReservationJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed durable capacity ledger. */
public class PostgresCapacityPort implements CapacityPort {

  private static final int COUNTER_ID = 1;
  private static final String STATUS_RESERVED = "RESERVED";
  private static final String STATUS_COMMITTED = "COMMITTED";
  private static final String STATUS_RELEASED = "RELEASED";

  private final CapacityReservationJpaRepository reservationRepository;
  private final CapacityCounterJpaRepository counterRepository;
  private final long maxBytes;
  private final Clock clock;

  /** Creates adapter. */
  public PostgresCapacityPort(
      final CapacityReservationJpaRepository reservationRepository,
      final CapacityCounterJpaRepository counterRepository,
      final long maxBytes,
      final Clock clock) {
    if (reservationRepository == null || counterRepository == null || clock == null) {
      throw new IllegalArgumentException("dependencies must not be null");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be greater than zero");
    }
    this.reservationRepository = reservationRepository;
    this.counterRepository = counterRepository;
    this.maxBytes = maxBytes;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean canReserve(final long expectedStorageBytes) {
    if (expectedStorageBytes <= 0) {
      return false;
    }
    final long occupiedBytes = currentOccupiedBytes();
    return occupiedBytes + expectedStorageBytes <= maxBytes;
  }

  @Override
  @Transactional
  public void reserve(final String reservationKey, final long expectedStorageBytes) {
    final String normalizedKey = normalizeKey(reservationKey);
    validateExpectedStorageBytes(expectedStorageBytes);

    final CapacityCounterJpaEntity counter = getCounterForUpdate();
    final Optional<CapacityReservationJpaEntity> existingReservation =
        reservationRepository.findByReservationKeyForUpdate(normalizedKey);

    if (existingReservation.isPresent()) {
      ensureIdempotentReservation(expectedStorageBytes, existingReservation.get());
      return;
    }

    if (counter.getOccupiedBytes() + expectedStorageBytes > maxBytes) {
      throw new IllegalArgumentException("Insufficient capacity");
    }

    final Instant now = clock.instant();
    final CapacityReservationJpaEntity entity = new CapacityReservationJpaEntity();
    entity.setReservationKey(normalizedKey);
    entity.setExpectedStorageBytes(expectedStorageBytes);
    entity.setStatus(STATUS_RESERVED);
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    reservationRepository.save(entity);

    counter.setOccupiedBytes(counter.getOccupiedBytes() + expectedStorageBytes);
    counterRepository.save(counter);
  }

  @Override
  @Transactional
  public void commit(final String reservationKey) {
    final String normalizedKey = normalizeKey(reservationKey);
    final CapacityReservationJpaEntity reservation =
        reservationRepository
            .findByReservationKeyForUpdate(normalizedKey)
            .orElseThrow(() -> new IllegalArgumentException("Reservation key not found"));

    if (STATUS_RELEASED.equals(reservation.getStatus())) {
      throw new IllegalArgumentException("Reservation key is already released");
    }
    if (STATUS_COMMITTED.equals(reservation.getStatus())) {
      return;
    }

    reservation.setStatus(STATUS_COMMITTED);
    reservation.setUpdatedAt(clock.instant());
    reservationRepository.save(reservation);
  }

  @Override
  @Transactional
  public void release(final String reservationKey) {
    final String normalizedKey = normalizeKey(reservationKey);
    final CapacityCounterJpaEntity counter = getCounterForUpdate();
    final Optional<CapacityReservationJpaEntity> optionalReservation =
        reservationRepository.findByReservationKeyForUpdate(normalizedKey);

    if (optionalReservation.isEmpty()) {
      return;
    }

    final CapacityReservationJpaEntity reservation = optionalReservation.get();
    if (STATUS_RELEASED.equals(reservation.getStatus())) {
      return;
    }

    final long nextOccupiedBytes =
        counter.getOccupiedBytes() - reservation.getExpectedStorageBytes();
    if (nextOccupiedBytes < 0L) {
      throw new IllegalStateException("Occupied bytes cannot be negative");
    }

    reservation.setStatus(STATUS_RELEASED);
    reservation.setUpdatedAt(clock.instant());
    reservationRepository.save(reservation);

    counter.setOccupiedBytes(nextOccupiedBytes);
    counterRepository.save(counter);
  }

  private long currentOccupiedBytes() {
    return counterRepository
        .findById(COUNTER_ID)
        .map(CapacityCounterJpaEntity::getOccupiedBytes)
        .orElse(0L);
  }

  private CapacityCounterJpaEntity getCounterForUpdate() {
    return counterRepository
        .findByIdForUpdate(COUNTER_ID)
        .orElseThrow(() -> new IllegalStateException("Capacity counter row is not initialized"));
  }

  private String normalizeKey(final String reservationKey) {
    if (reservationKey == null || reservationKey.isBlank()) {
      throw new IllegalArgumentException("reservationKey must not be blank");
    }
    return reservationKey.trim();
  }

  private void validateExpectedStorageBytes(final long expectedStorageBytes) {
    if (expectedStorageBytes <= 0) {
      throw new IllegalArgumentException("expectedStorageBytes must be greater than zero");
    }
  }

  private void ensureIdempotentReservation(
      final long expectedStorageBytes, final CapacityReservationJpaEntity existing) {
    if (existing.getExpectedStorageBytes() != expectedStorageBytes) {
      throw new IllegalArgumentException(
          "Reservation key already exists with different expectedStorageBytes");
    }
    if (STATUS_RELEASED.equals(existing.getStatus())) {
      throw new IllegalArgumentException("Reservation key is already released");
    }
  }
}
