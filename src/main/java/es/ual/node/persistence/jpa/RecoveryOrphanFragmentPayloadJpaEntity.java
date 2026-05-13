package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA entity for persisted recovery-domain orphan fragment payload bytes. */
@Entity
@Table(name = "recovery_orphan_fragment_payload")
public class RecoveryOrphanFragmentPayloadJpaEntity {

  @Id
  @Column(name = "fragment_id", nullable = false, length = 128)
  private String fragmentId;

  @Column(name = "payload", nullable = false)
  private byte[] payload;

  public String getFragmentId() {
    return fragmentId;
  }

  public void setFragmentId(final String fragmentId) {
    this.fragmentId = fragmentId;
  }

  public byte[] getPayload() {
    return payload;
  }

  public void setPayload(final byte[] payload) {
    this.payload = payload;
  }
}
