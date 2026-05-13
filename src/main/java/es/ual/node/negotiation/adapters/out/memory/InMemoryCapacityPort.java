package es.ual.node.negotiation.adapters.out.memory;

import es.ual.node.negotiation.ports.out.CapacityPort;
import java.util.HashMap;
import java.util.Map;

/** In-memory capacity adapter for negotiation validation. */
public class InMemoryCapacityPort implements CapacityPort {

  private final long maxBytes;
  private final Map<String, CapacityReservation> reservations = new HashMap<>();
  private long occupiedBytes;

  private enum ReservationStatus {
    RESERVED,
    COMMITTED,
    RELEASED
  }

  private static final class CapacityReservation {

    private final long expectedStorageBytes;
    private ReservationStatus status;

    private CapacityReservation(final long expectedStorageBytes, final ReservationStatus status) {
      this.expectedStorageBytes = expectedStorageBytes;
      this.status = status;
    }
  }

  /**
   * Creates capacity adapter.
   *
   * @param maxBytes total reservable bytes
   */
  public InMemoryCapacityPort(final long maxBytes) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be greater than zero");
    }
    this.maxBytes = maxBytes;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized boolean canReserve(final long expectedStorageBytes) {
    if (expectedStorageBytes <= 0) {
      return false;
    }
    return occupiedBytes + expectedStorageBytes <= maxBytes;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized void reserve(final String reservationKey, final long expectedStorageBytes) {
    final String normalizedKey = normalizeKey(reservationKey);
    validateExpectedStorageBytes(expectedStorageBytes);

    final CapacityReservation existing = reservations.get(normalizedKey);
    if (existing != null) {
      ensureIdempotentReservation(normalizedKey, expectedStorageBytes, existing);
      return;
    }

    if (!canReserve(expectedStorageBytes)) {
      throw new IllegalArgumentException("Insufficient capacity");
    }

    reservations.put(
        normalizedKey, new CapacityReservation(expectedStorageBytes, ReservationStatus.RESERVED));
    occupiedBytes += expectedStorageBytes;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized void commit(final String reservationKey) {
    final CapacityReservation existing = getRequiredReservation(reservationKey);
    if (existing.status == ReservationStatus.RELEASED) {
      throw new IllegalArgumentException("Reservation key is already released");
    }
    if (existing.status == ReservationStatus.COMMITTED) {
      return;
    }
    existing.status = ReservationStatus.COMMITTED;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized void release(final String reservationKey) {
    final String normalizedKey = normalizeKey(reservationKey);
    final CapacityReservation existing = reservations.get(normalizedKey);
    if (existing == null || existing.status == ReservationStatus.RELEASED) {
      return;
    }

    existing.status = ReservationStatus.RELEASED;
    occupiedBytes -= existing.expectedStorageBytes;

    if (occupiedBytes < 0L) {
      throw new IllegalStateException("Occupied bytes cannot be negative");
    }
  }

  private CapacityReservation getRequiredReservation(final String reservationKey) {
    final String normalizedKey = normalizeKey(reservationKey);
    final CapacityReservation existing = reservations.get(normalizedKey);
    if (existing == null) {
      throw new IllegalArgumentException("Reservation key not found");
    }
    return existing;
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
      final String reservationKey,
      final long expectedStorageBytes,
      final CapacityReservation existing) {
    if (existing.expectedStorageBytes != expectedStorageBytes) {
      throw new IllegalArgumentException(
          "Reservation key already exists with different expectedStorageBytes");
    }
    if (existing.status == ReservationStatus.RELEASED) {
      throw new IllegalArgumentException("Reservation key is already released");
    }
  }
}
