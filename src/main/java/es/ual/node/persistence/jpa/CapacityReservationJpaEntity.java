package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for durable capacity reservation ledger. */
@Entity
@Table(name = "capacity_reservation")
public class CapacityReservationJpaEntity {

  @Id
  @Column(name = "reservation_key", nullable = false, length = 128)
  private String reservationKey;

  @Column(name = "expected_storage_bytes", nullable = false)
  private long expectedStorageBytes;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public String getReservationKey() {
    return reservationKey;
  }

  public void setReservationKey(final String reservationKey) {
    this.reservationKey = reservationKey;
  }

  public long getExpectedStorageBytes() {
    return expectedStorageBytes;
  }

  public void setExpectedStorageBytes(final long expectedStorageBytes) {
    this.expectedStorageBytes = expectedStorageBytes;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(final String status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
