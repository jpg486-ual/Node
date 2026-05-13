package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA entity for aggregate capacity usage counter. */
@Entity
@Table(name = "capacity_counter")
public class CapacityCounterJpaEntity {

  @Id
  @Column(name = "id", nullable = false)
  private Integer id;

  @Column(name = "occupied_bytes", nullable = false)
  private long occupiedBytes;

  public Integer getId() {
    return id;
  }

  public void setId(final Integer id) {
    this.id = id;
  }

  public long getOccupiedBytes() {
    return occupiedBytes;
  }

  public void setOccupiedBytes(final long occupiedBytes) {
    this.occupiedBytes = occupiedBytes;
  }
}
