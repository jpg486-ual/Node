package es.ual.node.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA entity for registration invitation codes. */
@Entity
@Table(name = "registration_code")
public class RegistrationCodeJpaEntity {

  @Id
  @Column(name = "code", nullable = false, length = 16)
  private String code;

  @Column(name = "quota_mb", nullable = false)
  private int quotaMb;

  @Column(name = "role", nullable = false, length = 32)
  private String role;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used", nullable = false)
  private boolean used;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getCode() {
    return code;
  }

  public void setCode(final String code) {
    this.code = code;
  }

  public int getQuotaMb() {
    return quotaMb;
  }

  public void setQuotaMb(final int quotaMb) {
    this.quotaMb = quotaMb;
  }

  public String getRole() {
    return role;
  }

  public void setRole(final String role) {
    this.role = role;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(final Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public boolean isUsed() {
    return used;
  }

  public void setUsed(final boolean used) {
    this.used = used;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void setUsedAt(final Instant usedAt) {
    this.usedAt = usedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(final Instant createdAt) {
    this.createdAt = createdAt;
  }
}
