package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for used nonces. */
@Entity
@Table(name = "used_nonce")
public class NonceJpaEntity {

  @Id
  @Column(name = "nonce", nullable = false, length = 256)
  private String nonce;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  public String getNonce() {
    return nonce;
  }

  public void setNonce(final String nonce) {
    this.nonce = nonce;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(final Instant expiresAt) {
    this.expiresAt = expiresAt;
  }
}
