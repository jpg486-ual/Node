package es.ual.node.userregistration.domain;

import java.time.Instant;

/** Immutable user account metadata. */
public record UserAccount(
    String username, String passwordHash, int quotaMb, UserRole role, Instant createdAt) {

  /**
   * Creates validated account.
   *
   * @param username username
   * @param passwordHash password hash
   * @param quotaMb quota in MB
   * @param role RBAC role
   * @param createdAt creation instant
   */
  public UserAccount {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (passwordHash == null || passwordHash.isBlank()) {
      throw new IllegalArgumentException("passwordHash must not be blank");
    }
    if (quotaMb <= 0) {
      throw new IllegalArgumentException("quotaMb must be greater than zero");
    }
    if (role == null) {
      throw new IllegalArgumentException("role must not be null");
    }
    if (createdAt == null) {
      throw new IllegalArgumentException("createdAt must not be null");
    }
    username = username.trim();
    passwordHash = passwordHash.trim();
  }
}
