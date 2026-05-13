package es.ual.node.userregistration.domain;

import java.time.Instant;

/** Registration invitation code issued by node console. */
public record RegistrationCode(
    String code,
    int quotaMb,
    UserRole role,
    Instant expiresAt,
    boolean used,
    Instant usedAt,
    Instant createdAt) {

  /**
   * Creates validated invitation code.
   *
   * @param code invitation code
   * @param quotaMb quota in MB assigned to future account
   * @param role RBAC role assigned to the future account
   * @param expiresAt expiration instant
   * @param used usage flag
   * @param usedAt usage instant
   * @param createdAt creation instant
   */
  public RegistrationCode {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    if (quotaMb <= 0) {
      throw new IllegalArgumentException("quotaMb must be greater than zero");
    }
    if (role == null) {
      throw new IllegalArgumentException("role must not be null");
    }
    if (expiresAt == null) {
      throw new IllegalArgumentException("expiresAt must not be null");
    }
    if (createdAt == null) {
      throw new IllegalArgumentException("createdAt must not be null");
    }
    if (used && usedAt == null) {
      throw new IllegalArgumentException("usedAt must not be null when used is true");
    }
    code = code.trim().toUpperCase();
  }

  /**
   * Returns whether code can still be consumed.
   *
   * @param now current instant
   * @return true when code is active
   */
  public boolean isActiveAt(final Instant now) {
    return !used && now != null && expiresAt.isAfter(now);
  }
}
