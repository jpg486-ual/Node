package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for user accounts. */
@Entity
@Table(name = "user_account")
public class UserAccountJpaEntity {

  @Id
  @Column(name = "username", nullable = false, length = 128)
  private String username;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "quota_mb", nullable = false)
  private int quotaMb;

  @Column(name = "quota_used_bytes", nullable = false)
  private long quotaUsedBytes;

  @Column(name = "role", nullable = false, length = 32)
  private String role;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(final String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public int getQuotaMb() {
    return quotaMb;
  }

  public void setQuotaMb(final int quotaMb) {
    this.quotaMb = quotaMb;
  }

  public long getQuotaUsedBytes() {
    return quotaUsedBytes;
  }

  public void setQuotaUsedBytes(final long quotaUsedBytes) {
    this.quotaUsedBytes = quotaUsedBytes;
  }

  public String getRole() {
    return role;
  }

  public void setRole(final String role) {
    this.role = role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }
}
